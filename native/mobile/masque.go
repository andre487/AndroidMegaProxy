package mobile

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	quic "github.com/refraction-networking/uquic"
	"github.com/refraction-networking/uquic/http3"
	"github.com/refraction-networking/uquic/quicvarint"
	tls "github.com/refraction-networking/utls"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
)

var errMasqueSettings = errors.New("MASQUE server missing datagram or extended CONNECT settings")
var errMasqueCapsuleProtocol = errors.New("MASQUE response missing Capsule-Protocol")

type masqueDialer struct {
	hop           string
	jump          *masqueDialer
	probePeerMTU  bool
	config        config
	protector     Protector
	reporter      Reporter
	stats         *connectionStats
	mu            sync.Mutex
	closed        bool
	opening       chan struct{}
	openingCancel context.CancelFunc
	session       *masqueSession
	draining      map[*masqueSession]struct{}
	dohClient     *http.Client
	dohInFlight   chan struct{}
	connections   chan struct{}
}

type masqueSession struct {
	peerMTU              *jumpMTUPacketConn
	sourceCIDLength      int
	once                 sync.Once
	id                   uint64
	transport            *quic.UTransport
	packet               net.PacketConn
	conn                 *quic.Conn
	client               *http3.ClientConn
	maxDatagramFrameSize uint64
	metrics              *measuredQUICConn
}

func (s *masqueSession) close() {
	s.once.Do(func() {
		if s.metrics != nil {
			s.metrics.Close()
		}
		_ = s.conn.CloseWithError(0, "")
		_ = s.transport.Close()
		_ = s.packet.Close()
	})
}

func (d *masqueDialer) Close() error {
	d.mu.Lock()
	d.closed = true
	if d.openingCancel != nil {
		d.openingCancel()
	}
	s, client := d.session, d.dohClient
	draining := d.draining
	d.draining = nil
	d.session, d.dohClient = nil, nil
	d.mu.Unlock()
	if client != nil {
		client.CloseIdleConnections()
	}
	if s != nil {
		s.close()
	}
	for old := range draining {
		old.close()
	}
	if d.jump != nil {
		_ = d.jump.Close()
	}
	return nil
}

// Only the first caller performs the handshake. Stop cancels it, and callers
// waiting for that handshake retain their own cancellation deadlines.
func (d *masqueDialer) getSession(ctx context.Context) (*masqueSession, error) {
	for {
		d.mu.Lock()
		if d.closed {
			d.mu.Unlock()
			return nil, net.ErrClosed
		}
		if s := d.session; s != nil && s.client.CanTakeNewRequest() {
			d.mu.Unlock()
			return s, nil
		}
		if waiting := d.opening; waiting != nil {
			d.mu.Unlock()
			select {
			case <-waiting:
				continue
			case <-ctx.Done():
				return nil, ctx.Err()
			}
		}
		if d.session != nil {
			old := d.session
			if old.conn.Context().Err() == nil {
				if d.draining == nil {
					d.draining = make(map[*masqueSession]struct{})
				}
				d.draining[old] = struct{}{}
				context.AfterFunc(old.conn.Context(), func() {
					old.close()
					d.mu.Lock()
					delete(d.draining, old)
					d.mu.Unlock()
				})
				report(d.reporter, "event=masque_session result=draining reason=goaway quic_session=%d", old.id)
			} else {
				old.close()
			}
			d.session = nil
		}
		d.opening = make(chan struct{})
		handshake, cancel := context.WithTimeout(ctx, 15*time.Second)
		d.openingCancel = cancel
		d.mu.Unlock()
		s, err := d.dialSession(handshake)
		cancel()
		if err != nil {
			reason := errorClass(err)
			stage := "tls_handshake"
			if errors.Is(err, errMasqueSettings) {
				stage = "server_settings"
			}
			report(d.reporter, "event=connection protocol=http3 stage=%s result=failed reason=%s dpi_hint=%s %s hop=%s", stage, reason, tlsInterferenceHint(reason), quicErrorDetails(err), d.logHop())
		}
		d.mu.Lock()
		close(d.opening)
		d.opening, d.openingCancel = nil, nil
		if d.closed && s != nil {
			s.close()
			s = nil
			err = net.ErrClosed
		}
		d.session = s
		d.mu.Unlock()
		return s, err
	}
}

func (d *masqueDialer) dialSession(ctx context.Context) (session *masqueSession, err error) {
	stage := "tls_handshake"
	defer func() {
		if err != nil {
			var hopError *masqueHopError
			if !errors.As(err, &hopError) {
				err = &masqueHopError{hop: d.logHop(), stage: stage, err: err}
			}
		}
	}()
	spec, err := d.config.quicSpec()
	if err != nil {
		return nil, err
	}
	var address *net.UDPAddr
	var packet net.PacketConn
	packetMTU := 0
	if d.jump != nil {
		// The destination hostname is resolved by the jump, never on Android.
		// This address identifies the fixed packet association; no socket dials it.
		address = &net.UDPAddr{IP: net.IPv4(192, 0, 2, 1), Port: d.config.Port}
		stream, err := d.jump.openTunnel(ctx, d.config.displayAddress(), true)
		if err != nil {
			var hopError *masqueHopError
			if errors.As(err, &hopError) {
				return nil, err
			}
			return nil, &masqueHopError{hop: "jump", stage: "connect_udp", err: err}
		}
		// The association's stream ID can grow after reconnects / GOAWAY.
		packetMTU = nestedPacketMTU(stream.session, quicvarint.Len(uint64(stream.StreamID()/4)))
		if packetMTU < minimumNestedPacketSize {
			_ = stream.Close()
			return nil, errMasqueSettings
		}
		packet = d.jump.udpPacketConn(stream, address)
		// Encapsulated QUIC must fit an outer DATAGRAM. Keep the browser TLS
		// preset, but constrain path padding and advertised receive MTU.
		spec.InitialPacketSpec.FrameBuilder = nil
		spec.UDPDatagramMinSize = 1280
		for _, extension := range spec.ClientHelloSpec.Extensions {
			if params, ok := extension.(*tls.QUICTransportParametersExtension); ok {
				for i, parameter := range params.TransportParameters {
					if _, ok := parameter.(tls.MaxUDPPayloadSize); ok {
						params.TransportParameters[i] = tls.MaxUDPPayloadSize(packetMTU)
					}
				}
			}
		}
	} else {
		address, err = net.ResolveUDPAddr("udp", d.config.address())
		if err != nil {
			return nil, err
		}
		control := (&httpsConnectDialer{protector: d.protector}).protectedDialer().Control
		listen := net.ListenConfig{Control: control}
		network, bind := "udp4", "0.0.0.0:0"
		if address.IP.To4() == nil {
			network, bind = "udp6", "[::]:0"
		}
		packet, err = listen.ListenPacket(ctx, network, bind)
		if err != nil {
			return nil, fmt.Errorf("protect QUIC socket: %w", err)
		}
	}
	var peerMTU *jumpMTUPacketConn
	if d.probePeerMTU || d.jump != nil {
		observed := &jumpMTUPacketConn{PacketConn: packet, peer: address}
		packet, peerMTU = observed, observed
		if d.jump == nil {
			packet = &jumpMTUSocket{observed}
		}
	}
	transport := &quic.UTransport{Transport: &quic.Transport{Conn: packet}, QUICSpec: &spec}
	options := &quic.Config{EnableDatagrams: true, KeepAlivePeriod: 20 * time.Second, HandshakeIdleTimeout: 15 * time.Second, MaxIdleTimeout: 60 * time.Second}
	if d.jump != nil {
		options.InitialPacketSize = uint16(packetMTU)
		options.DisablePathMTUDiscovery = true
	}
	conn, err := transport.Dial(ctx, address, &tls.Config{
		ServerName: d.config.Host, NextProtos: []string{"h3"}, MinVersion: tls.VersionTLS13,
		InsecureSkipVerify: d.config.AllowInvalidProxyCertificate,
	}, options)
	if err != nil {
		_ = transport.Close()
		_ = packet.Close()
		return nil, fmt.Errorf("MASQUE QUIC handshake: %w", err)
	}
	h3 := &http3.Transport{EnableDatagrams: true, DisableCompression: true, MaxResponseHeaderBytes: 64 * 1024}
	s := &masqueSession{peerMTU: peerMTU, sourceCIDLength: spec.InitialPacketSpec.SrcConnIDLength, id: nextDiagnosticConnectionID(), transport: transport, packet: packet, conn: conn, client: h3.NewClientConn(conn)}
	for _, extension := range spec.ClientHelloSpec.Extensions {
		if params, ok := extension.(*tls.QUICTransportParametersExtension); ok {
			for _, param := range params.TransportParameters {
				if maximum, ok := param.(tls.MaxDatagramFrameSize); ok {
					s.maxDatagramFrameSize = uint64(maximum)
				}
			}
		}
	}
	stage = "server_settings"
	select {
	case <-s.client.ReceivedSettings():
	case <-ctx.Done():
		s.close()
		return nil, ctx.Err()
	case <-conn.Context().Done():
		cause := context.Cause(conn.Context())
		s.close()
		return nil, cause
	}
	if !s.client.Settings().EnableDatagrams || !s.client.Settings().EnableExtendedConnect {
		s.close()
		return nil, fmt.Errorf("%w: datagrams=%t extended_connect=%t", errMasqueSettings, s.client.Settings().EnableDatagrams, s.client.Settings().EnableExtendedConnect)
	}
	if d.probePeerMTU || d.jump != nil {
		stage = "path_mtu"
		if err := waitForMasqueMTU(ctx, s, d.config.Host, packetMTU); err != nil {
			s.close()
			return nil, err
		}
	}
	if d.jump != nil && conn.DatagramPayloadLimit() < 1350+8+1 {
		s.close()
		return nil, fmt.Errorf("%w: nested datagram budget too small", errMasqueSettings)
	}
	if peerMTU != nil {
		report(d.reporter, "event=masque_mtu hop=%s datagram_payload_limit=%d largest_peer_packet=%d tunnel_packet_mtu=%d quic_session=%d", d.logHop(), conn.DatagramPayloadLimit(), peerMTU.largest.Load(), packetMTU, s.id)
	}
	if d.stats != nil {
		s.metrics = d.stats.trackQUIC(conn)
	}
	state := conn.ConnectionState()
	report(d.reporter, "event=masque_session result=established protocol=http3 hop=%s multiplexed=true fingerprint=%s certificate_verification_enabled=%t %s quic_version=%d datagrams=true extended_connect=true quic_session=%d", d.logHop(), d.config.Profile, !d.config.AllowInvalidProxyCertificate, tlsNegotiationDetails(state.TLS.Version, state.TLS.CipherSuite, state.TLS.NegotiatedProtocol, state.TLS.DidResume), state.Version, s.id)
	return s, nil
}

func (c config) quicSpec() (quic.QUICSpec, error) {
	id := quic.QUICChrome_146
	if net.ParseIP(c.DialHost).To4() == nil {
		id = quic.QUICChrome_146_IPv6
	}
	switch c.Profile {
	case "CHROME_ANDROID":
	case "FIREFOX_ANDROID":
		id = quic.QUICFirefox_116
	case "RANDOMIZED":
	case "CUSTOM":
	default:
		return quic.QUICSpec{}, fmt.Errorf("unsupported QUIC fingerprint %q", c.Profile)
	}
	spec, err := quic.QUICID2Spec(id)
	if err != nil {
		return spec, err
	}
	if c.Profile == "RANDOMIZED" {
		spec.RandomizeTransportParameters = true
		spec.ClientHelloSpec.Extensions = tls.ShuffleChromeTLSExtensions(spec.ClientHelloSpec.Extensions)
	}
	if c.Profile == "CUSTOM" {
		var parameters *tls.QUICTransportParametersExtension
		for _, extension := range spec.ClientHelloSpec.Extensions {
			if p, ok := extension.(*tls.QUICTransportParametersExtension); ok {
				parameters = p
			}
		}
		custom, err := ja3ClientHelloSpec(c.CustomJA3, c.Host, parameters)
		if err != nil {
			return spec, err
		}
		spec.ClientHelloSpec = custom
	}
	return spec, nil
}

// Errors from a retired session must not restart its healthy replacement.
func (d *masqueDialer) sessionHealthy() bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.session != nil && d.session.conn.Context().Err() == nil
}

func (d *masqueDialer) checkTarget(target string) error {
	host, port, err := net.SplitHostPort(target)
	if err != nil || host == "" {
		return errors.New("invalid MASQUE destination")
	}
	p, err := strconv.Atoi(port)
	if err != nil || p < 1 || p > 65535 {
		return errors.New("invalid MASQUE destination port")
	}
	if ip := net.ParseIP(host); !d.config.AllowIPv6 && ip != nil && ip.To4() == nil {
		return errors.New("IPv6 destination blocked by IPv4-only mode")
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.closed {
		return net.ErrClosed
	}
	return nil
}

func (d *masqueDialer) openTunnel(ctx context.Context, target string, udp bool) (result *masqueStreamConn, err error) {
	started := time.Now()
	connectionID := nextDiagnosticConnectionID()
	stage := "stream_open"
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	s, err := d.getSession(ctx)
	if err != nil {
		return nil, err
	}
	sessionID := s.id
	proxyContext := s.conn.Context()
	defer func() {
		if err != nil {
			if ctx.Err() != nil {
				err = ctx.Err()
			}
			reason := errorClass(err)
			scope, hint := "target", "none"
			if proxyContext.Err() != nil && !d.sessionHealthy() {
				scope, hint = "proxy", tlsInterferenceHint(reason)
			}
			var rejected *masqueConnectError
			if errors.As(err, &rejected) {
				report(d.reporter, "event=connection protocol=http3 conn=%d quic_session=%d stage=connect_response result=rejected status=%d reason=%s udp=%t", connectionID, sessionID, rejected.status, reason, udp)
			} else {
				report(d.reporter, "event=connection protocol=http3 conn=%d quic_session=%d stage=%s result=failed reason=%s dpi_hint=%s scope=%s udp=%t elapsed_ms=%d %s", connectionID, sessionID, stage, reason, hint, scope, udp, time.Since(started).Milliseconds(), quicErrorDetails(err))
			}
		}
	}()
	stream, err := s.client.OpenRequestStream(ctx)
	if err != nil && !s.client.CanTakeNewRequest() && ctx.Err() == nil {
		// GOAWAY can race the session lookup. Retry only before sending CONNECT.
		s, err = d.getSession(ctx)
		if err == nil {
			sessionID = s.id
			proxyContext = s.conn.Context()
			stream, err = s.client.OpenRequestStream(ctx)
		}
	}
	if err != nil {
		return nil, err
	}
	c := &masqueStreamConn{session: s, RequestStream: stream, connectionID: connectionID, sessionID: s.id, local: s.conn.LocalAddr(), remote: s.conn.RemoteAddr(), maxDatagramFrameSize: s.maxDatagramFrameSize, proxyContext: s.conn.Context()}
	stop := context.AfterFunc(ctx, func() { _ = c.Close() })
	defer stop()
	request := &http.Request{Method: http.MethodConnect, URL: &url.URL{Scheme: "https", Host: target}, Host: target, Header: make(http.Header)}
	if udp {
		host, port, _ := net.SplitHostPort(target)
		request.Proto = "connect-udp"
		request.Host, request.URL.Host = d.config.displayAddress(), d.config.displayAddress()
		request.URL.Path = "/.well-known/masque/udp/" + host + "/" + port + "/"
		request.URL.RawPath = "/.well-known/masque/udp/" + url.QueryEscape(host) + "/" + port + "/"
		request.Header.Set("Capsule-Protocol", "?1")
	}
	request.Header.Set("Proxy-Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(d.config.Username+":"+d.config.Password)))
	stage = "connect_write"
	if err = stream.SendRequestHeader(request); err != nil {
		_ = c.Close()
		return nil, err
	}
	stage = "connect_response"
	response, err := stream.ReadResponse()
	if err != nil {
		_ = c.Close()
		return nil, err
	}
	if response.StatusCode != http.StatusOK {
		_ = c.Close()
		return nil, &masqueConnectError{status: response.StatusCode, udp: udp}
	}
	if udp && response.Header.Get("Capsule-Protocol") != "?1" {
		_ = c.Close()
		return nil, errMasqueCapsuleProtocol
	}
	if !stop() || ctx.Err() != nil {
		_ = c.Close()
		return nil, ctx.Err()
	}
	report(d.reporter, "event=connection protocol=http3 http_version=HTTP/3 stage=tunnel result=established stream_multiplexed=true udp=%t conn=%d quic_session=%d elapsed_ms=%d hop=%s", udp, connectionID, s.id, time.Since(started).Milliseconds(), d.logHop())
	return c, nil
}

func (d *masqueDialer) connectTarget(ctx context.Context, target string) (net.Conn, error) {
	if err := d.checkTarget(target); err != nil {
		return nil, err
	}
	var c net.Conn
	var err error
	if d.config.BypassLocalNetworks && isLocalNetworkTarget(target) {
		c, err = (&httpsConnectDialer{protector: d.protector}).protectedDialer().DialContext(ctx, "tcp", target)
	} else {
		c, err = d.openTunnel(ctx, target, false)
	}
	if err != nil {
		return nil, err
	}
	result := &diagnosticConn{Conn: c, reporter: d.reporter, stats: d.stats}
	if stream, ok := c.(*masqueStreamConn); ok {
		result.connectionID, result.proxyContext = stream.connectionID, stream.proxyContext
		result.proxyHealthy = d.sessionHealthy
	} else {
		result.connectionID = nextDiagnosticConnectionID()
	}
	return result, nil
}

func (d *masqueDialer) DialContext(ctx context.Context, metadata *M.Metadata) (net.Conn, error) {
	d.mu.Lock()
	if d.connections == nil {
		d.connections = make(chan struct{}, 256)
	}
	slots := d.connections
	d.mu.Unlock()
	select {
	case slots <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	c, err := d.connectTarget(ctx, metadata.DestinationAddress())
	if err != nil {
		<-slots
		return nil, err
	}
	return &slotConn{Conn: c, release: func() { <-slots }}, nil
}

func (d *masqueDialer) DialUDP(metadata *M.Metadata) (net.PacketConn, error) {
	target := metadata.DestinationAddress()
	if err := d.checkTarget(target); err != nil {
		return nil, err
	}
	if metadata.DstPort == 53 {
		d.mu.Lock()
		if d.closed {
			d.mu.Unlock()
			return nil, net.ErrClosed
		}
		if d.dohClient == nil {
			d.dohClient = newDoHHTTPClient(d.connectTarget)
			d.dohInFlight = make(chan struct{}, 8)
		}
		client, limiter := d.dohClient, d.dohInFlight
		d.mu.Unlock()
		return newDoHPacketConnWithClient(d.config, d.reporter, d.connectTarget, d.config.DoHURL, client, limiter), nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if d.config.BypassLocalNetworks && isLocalNetworkTarget(target) {
		c, err := (&httpsConnectDialer{protector: d.protector}).protectedDialer().DialContext(ctx, "udp", target)
		if err != nil {
			return nil, err
		}
		return &masqueDirectPacketConn{Conn: &diagnosticConn{Conn: c, stats: d.stats, reporter: d.reporter, connectionID: nextDiagnosticConnectionID()}}, nil
	}
	stream, err := d.openTunnel(ctx, target, true)
	if err != nil {
		return nil, err
	}
	remote := &net.UDPAddr{IP: net.IP(metadata.DstIP.AsSlice()), Port: int(metadata.DstPort)}
	return d.udpPacketConn(stream, remote), nil
}

func (d *masqueDialer) udpPacketConn(stream *masqueStreamConn, remote *net.UDPAddr) net.PacketConn {
	p := &masquePacketConn{masqueStreamConn: stream, remote: remote, packets: make(chan dnsReply, 16), stats: d.stats, reporter: d.reporter, proxyHealthy: d.sessionHealthy}
	go p.receive()
	return p
}

type masqueConnectError struct {
	status int
	udp    bool
}

type masqueHopError struct {
	hop, stage string
	err        error
}

func (e *masqueHopError) Error() string { return e.err.Error() }
func (e *masqueHopError) Unwrap() error { return e.err }
func (d *masqueDialer) logHop() string {
	if d.hop != "" {
		return d.hop
	}
	if d.jump != nil {
		return "destination"
	}
	return "proxy"
}

func (e *masqueConnectError) Error() string {
	if e.status == http.StatusProxyAuthRequired {
		return "MASQUE proxy authentication failed (status 407)"
	}
	return fmt.Sprintf("MASQUE CONNECT returned status %d", e.status)
}

type masqueStreamConn struct {
	session *masqueSession
	*http3.RequestStream
	local, remote           net.Addr
	connectionID, sessionID uint64
	maxDatagramFrameSize    uint64
	closed                  atomic.Bool
	readClosed              atomic.Bool
	proxyContext            context.Context
	once                    sync.Once
}

func (c *masqueStreamConn) LocalAddr() net.Addr  { return c.local }
func (c *masqueStreamConn) RemoteAddr() net.Addr { return c.remote }
func (c *masqueStreamConn) Read(b []byte) (int, error) {
	n, err := c.RequestStream.Read(b)
	if err != nil && (c.closed.Load() || c.readClosed.Load()) {
		return n, net.ErrClosed
	}
	return n, err
}
func (c *masqueStreamConn) Write(b []byte) (int, error) {
	n, err := c.RequestStream.Write(b)
	if err != nil && c.closed.Load() {
		return n, net.ErrClosed
	}
	return n, err
}
func (c *masqueStreamConn) CloseRead() error {
	c.readClosed.Store(true)
	c.CancelRead(quic.StreamErrorCode(http3.ErrCodeRequestCanceled))
	return nil
}

// Send FIN without canceling the response: TCP half-close must preserve download.
func (c *masqueStreamConn) CloseWrite() error { return c.RequestStream.Close() }

func (c *masqueStreamConn) Close() error {
	c.once.Do(func() {
		c.closed.Store(true)
		c.CancelRead(quic.StreamErrorCode(http3.ErrCodeRequestCanceled))
		c.CancelWrite(quic.StreamErrorCode(http3.ErrCodeRequestCanceled))
	})
	return nil
}

type masquePacketConn struct {
	*masqueStreamConn
	remote                      net.Addr
	packets                     chan dnsReply
	readDeadline, writeDeadline packetDeadline
	stats                       *connectionStats
	reporter                    Reporter
	oversized                   sync.Once
	proxyHealthy                func() bool
}

func (p *masquePacketConn) receive() {
	defer close(p.packets)
	// Drain the reliable capsule stream as well: EOF/reset ends the UDP tunnel.
	go func() {
		_, err := io.Copy(io.Discard, p.RequestStream)
		if !p.closed.Load() && p.Context().Err() == nil {
			reason := errorClass(err)
			if err == nil {
				reason = "eof"
			}
			report(p.reporter, "event=masque_udp conn=%d quic_session=%d result=closed reason=%s %s", p.connectionID, p.sessionID, reason, quicErrorDetails(err))
		}
		_ = p.Close()
	}()
	for {
		data, err := p.ReceiveDatagram(p.Context())
		if err != nil {
			if !p.closed.Load() && p.Context().Err() == nil && !errors.Is(err, io.EOF) && !errors.Is(err, net.ErrClosed) {
				reason := errorClass(err)
				scope, hint := "proxy", tlsInterferenceHint(reason)
				if p.proxyContext.Err() == nil || (p.proxyHealthy != nil && p.proxyHealthy()) {
					scope, hint = "target", "none"
				}
				report(p.reporter, "event=connection protocol=http3 stage=tunnel_io result=failed operation=udp_receive reason=%s dpi_hint=%s scope=%s conn=%d quic_session=%d %s", reason, hint, scope, p.connectionID, p.sessionID, quicErrorDetails(err))
			}
			return
		}
		reader := bytes.NewReader(data)
		id, err := quicvarint.Read(reader)
		if err != nil || id != 0 {
			continue
		}
		payload := data[len(data)-reader.Len():]
		select {
		case p.packets <- dnsReply{payload: payload}:
		case <-p.Context().Done():
			return
		}
	}
}

func (p *masquePacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	select {
	case <-p.Context().Done():
		return 0, nil, net.ErrClosed
	case <-p.readDeadline.wait():
		if p.closed.Load() {
			return 0, nil, net.ErrClosed
		}
		return 0, nil, os.ErrDeadlineExceeded
	case packet, ok := <-p.packets:
		if !ok {
			return 0, nil, net.ErrClosed
		}
		n := copy(b, packet.payload)
		if p.stats != nil {
			p.stats.downloadBytes.Add(uint64(n))
		}
		return n, p.remote, nil
	}
}
func (p *masquePacketConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	if addr == nil || addr.String() != p.remote.String() {
		return 0, errors.New("MASQUE UDP target cannot change")
	}
	select {
	case <-p.Context().Done():
		return 0, net.ErrClosed
	case <-p.writeDeadline.wait():
		if p.closed.Load() {
			return 0, net.ErrClosed
		}
		return 0, os.ErrDeadlineExceeded
	default:
	}
	// Keep packets within our advertised receive limit too: an echo-sized
	// response exceeding Firefox's limit makes GOST close the UDP association.
	size := uint64(len(b)+1) + uint64(quicvarint.Len(uint64(p.StreamID()/4)))
	if size+1+uint64(quicvarint.Len(size)) > p.maxDatagramFrameSize {
		maximum := int64(p.maxDatagramFrameSize) - 1 - int64(quicvarint.Len(p.maxDatagramFrameSize)) - int64(quicvarint.Len(uint64(p.StreamID()/4))) - 1
		p.reportOversized(maximum)
		return len(b), nil
	}
	data := make([]byte, len(b)+1)
	copy(data[1:], b)
	if err := p.SendDatagramWithCancel(data, p.writeDeadline.wait()); err != nil {
		if p.closed.Load() || p.Context().Err() != nil {
			return 0, net.ErrClosed
		}
		if errors.Is(err, context.Canceled) {
			return 0, os.ErrDeadlineExceeded
		}
		var tooLarge *quic.DatagramTooLargeError
		if errors.As(err, &tooLarge) {
			// UDP is lossy: dropping one unsupported packet must not stop tun2socks.
			p.reportOversized(tooLarge.MaxDatagramPayloadSize - 1)
			return len(b), nil
		}
		return 0, err
	}
	if p.stats != nil {
		p.stats.uploadBytes.Add(uint64(len(b)))
	}
	return len(b), nil
}
func (p *masquePacketConn) reportOversized(maximum int64) {
	p.oversized.Do(func() {
		report(p.reporter, "event=masque_udp result=dropped reason=datagram_too_large max_payload_bytes=%d conn=%d quic_session=%d", maximum, p.connectionID, p.sessionID)
	})
}

func (p *masquePacketConn) Close() error {
	err := p.masqueStreamConn.Close()
	p.readDeadline.stop()
	p.writeDeadline.stop()
	return err
}
func (p *masquePacketConn) SetReadDeadline(t time.Time) error  { p.readDeadline.set(t); return nil }
func (p *masquePacketConn) SetWriteDeadline(t time.Time) error { p.writeDeadline.set(t); return nil }
func (p *masquePacketConn) SetDeadline(t time.Time) error {
	_ = p.SetReadDeadline(t)
	return p.SetWriteDeadline(t)
}

// A connected protected UDP socket keeps local bypass pinned to its destination.
type masqueDirectPacketConn struct{ net.Conn }

func (p *masqueDirectPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	n, err := p.Read(b)
	return n, p.RemoteAddr(), err
}
func (p *masqueDirectPacketConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	if addr == nil || addr.String() != p.RemoteAddr().String() {
		return 0, errors.New("UDP target cannot change")
	}
	return p.Write(b)
}
