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
	"time"

	quic "github.com/refraction-networking/uquic"
	"github.com/refraction-networking/uquic/http3"
	"github.com/refraction-networking/uquic/quicvarint"
	tls "github.com/refraction-networking/utls"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
)

type masqueDialer struct {
	config        config
	protector     Protector
	reporter      Reporter
	stats         *connectionStats
	mu            sync.Mutex
	closed        bool
	opening       chan struct{}
	openingCancel context.CancelFunc
	session       *masqueSession
	dohClient     *http.Client
	dohInFlight   chan struct{}
	connections   chan struct{}
}

type masqueSession struct {
	transport *quic.UTransport
	packet    net.PacketConn
	conn      *quic.Conn
	client    *http3.ClientConn
}

func (s *masqueSession) close() {
	_ = s.conn.CloseWithError(0, "")
	_ = s.transport.Close()
	_ = s.packet.Close()
}

func (d *masqueDialer) Close() error {
	d.mu.Lock()
	d.closed = true
	if d.openingCancel != nil {
		d.openingCancel()
	}
	s, client := d.session, d.dohClient
	d.session, d.dohClient = nil, nil
	d.mu.Unlock()
	if client != nil {
		client.CloseIdleConnections()
	}
	if s != nil {
		s.close()
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
		if s := d.session; s != nil && s.conn.Context().Err() == nil {
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
			d.session.close()
			d.session = nil
		}
		d.opening = make(chan struct{})
		handshake, cancel := context.WithTimeout(ctx, 15*time.Second)
		d.openingCancel = cancel
		d.mu.Unlock()
		s, err := d.dialSession(handshake)
		cancel()
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

func (d *masqueDialer) dialSession(ctx context.Context) (*masqueSession, error) {
	spec, err := d.config.quicSpec()
	if err != nil {
		return nil, err
	}
	address, err := net.ResolveUDPAddr("udp", d.config.address())
	if err != nil {
		return nil, err
	}
	// Reuse the TCP socket protection callback before the UDP socket is bound.
	control := (&httpsConnectDialer{protector: d.protector}).protectedDialer().Control
	listen := net.ListenConfig{Control: control}
	network, bind := "udp4", "0.0.0.0:0"
	if address.IP.To4() == nil {
		network, bind = "udp6", "[::]:0"
	}
	packet, err := listen.ListenPacket(ctx, network, bind)
	if err != nil {
		return nil, fmt.Errorf("protect QUIC socket: %w", err)
	}
	transport := &quic.UTransport{Transport: &quic.Transport{Conn: packet}, QUICSpec: &spec}
	conn, err := transport.Dial(ctx, address, &tls.Config{
		ServerName: d.config.Host, NextProtos: []string{"h3"}, MinVersion: tls.VersionTLS13,
		InsecureSkipVerify: d.config.AllowInvalidProxyCertificate,
	}, &quic.Config{EnableDatagrams: true, KeepAlivePeriod: 20 * time.Second, HandshakeIdleTimeout: 15 * time.Second, MaxIdleTimeout: 60 * time.Second})
	if err != nil {
		_ = transport.Close()
		_ = packet.Close()
		return nil, fmt.Errorf("MASQUE QUIC handshake: %w", err)
	}
	h3 := &http3.Transport{EnableDatagrams: true, DisableCompression: true, MaxResponseHeaderBytes: 64 * 1024}
	s := &masqueSession{transport: transport, packet: packet, conn: conn, client: h3.NewClientConn(conn)}
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
		return nil, fmt.Errorf("MASQUE server settings: datagrams=%t extended_connect=%t", s.client.Settings().EnableDatagrams, s.client.Settings().EnableExtendedConnect)
	}
	report(d.reporter, "event=masque_session result=established protocol=http3 multiplexed=true fingerprint=%s certificate_verification_enabled=%t", d.config.Profile, !d.config.AllowInvalidProxyCertificate)
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

func (d *masqueDialer) openTunnel(ctx context.Context, target string, udp bool) (*masqueStreamConn, error) {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	s, err := d.getSession(ctx)
	if err != nil {
		return nil, err
	}
	stream, err := s.client.OpenRequestStream(ctx)
	if err != nil {
		return nil, err
	}
	c := &masqueStreamConn{RequestStream: stream, local: s.conn.LocalAddr(), remote: s.conn.RemoteAddr()}
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
	if err = stream.SendRequestHeader(request); err != nil {
		_ = c.Close()
		return nil, err
	}
	response, err := stream.ReadResponse()
	if err != nil {
		_ = c.Close()
		return nil, err
	}
	if response.StatusCode != http.StatusOK {
		_ = c.Close()
		return nil, fmt.Errorf("MASQUE CONNECT returned status %d", response.StatusCode)
	}
	if udp && response.Header.Get("Capsule-Protocol") != "?1" {
		_ = c.Close()
		return nil, errors.New("MASQUE response missing Capsule-Protocol")
	}
	if !stop() || ctx.Err() != nil {
		_ = c.Close()
		return nil, ctx.Err()
	}
	report(d.reporter, "event=connection protocol=http3 http_version=HTTP/3 stage=tunnel result=established stream_multiplexed=true udp=%t", udp)
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
	return &diagnosticConn{Conn: c, connectionID: nextDiagnosticConnectionID(), reporter: d.reporter, stats: d.stats}, nil
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
	p := &masquePacketConn{masqueStreamConn: stream, remote: remote, packets: make(chan dnsReply, 16), stats: d.stats}
	go p.receive()
	return p, nil
}

type masqueStreamConn struct {
	*http3.RequestStream
	local, remote net.Addr
	once          sync.Once
}

func (c *masqueStreamConn) LocalAddr() net.Addr  { return c.local }
func (c *masqueStreamConn) RemoteAddr() net.Addr { return c.remote }
func (c *masqueStreamConn) Close() error {
	c.once.Do(func() {
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
}

func (p *masquePacketConn) receive() {
	defer close(p.packets)
	// Drain the reliable capsule stream as well: EOF/reset ends the UDP tunnel.
	go func() { _, _ = io.Copy(io.Discard, p.RequestStream); _ = p.Close() }()
	for {
		data, err := p.ReceiveDatagram(p.Context())
		if err != nil {
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
		return 0, os.ErrDeadlineExceeded
	default:
	}
	data := make([]byte, len(b)+1)
	copy(data[1:], b)
	if err := p.SendDatagram(data); err != nil {
		return 0, err
	}
	if p.stats != nil {
		p.stats.uploadBytes.Add(uint64(len(b)))
	}
	return len(b), nil
}
func (p *masquePacketConn) Close() error {
	p.readDeadline.stop()
	p.writeDeadline.stop()
	return p.masqueStreamConn.Close()
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
