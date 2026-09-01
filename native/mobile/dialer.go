package mobile

import (
	"bufio"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	tls "github.com/refraction-networking/utls"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
)

var errUDPBlocked = errors.New("UDP is intentionally blocked")

// Protector is implemented by Android and calls VpnService.protect(fd).
type Protector interface{ Protect(fd int) bool }

type httpsConnectDialer struct {
	config       config
	protector    Protector
	reporter     Reporter
	cacheMu      sync.Mutex
	sessionCache tls.ClientSessionCache
	dohClient    *http.Client
	dohInFlight  chan struct{}
	connections  chan struct{}
	h2Session    *http2ConnectSession
	h2Disabled   bool
}

func (d *httpsConnectDialer) Close() error {
	d.cacheMu.Lock()
	defer d.cacheMu.Unlock()
	if d.dohClient != nil {
		d.dohClient.CloseIdleConnections()
		d.dohClient = nil
	}
	if d.h2Session != nil {
		_ = d.h2Session.close()
		d.h2Session = nil
	}
	return nil
}

func (d *httpsConnectDialer) DialContext(ctx context.Context, metadata *M.Metadata) (net.Conn, error) {
	d.cacheMu.Lock()
	if d.connections == nil {
		d.connections = make(chan struct{}, 256)
	}
	connections := d.connections
	d.cacheMu.Unlock()
	select {
	case connections <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	connection, err := d.connectTarget(ctx, metadata.DestinationAddress())
	if err != nil {
		<-connections
		return nil, err
	}
	return &slotConn{Conn: connection, release: func() { <-connections }}, nil
}

func (d *httpsConnectDialer) connectTarget(ctx context.Context, target string) (net.Conn, error) {
	connectionID := nextDiagnosticConnectionID()
	totalStarted := time.Now()
	if !d.config.AllowIPv6 {
		host, _, err := net.SplitHostPort(target)
		if err == nil {
			if ip := net.ParseIP(host); ip != nil && ip.To4() == nil {
				err = errors.New("IPv6 destination blocked by IPv4-only mode")
				report(d.reporter, "event=connection conn=%d stage=policy result=blocked reason=ipv6_disabled", connectionID)
				return nil, err
			}
		}
	}
	if d.config.BypassLocalNetworks && isLocalNetworkTarget(target) {
		report(d.reporter, "event=connection conn=%d mode=direct stage=tcp_connect result=started", connectionID)
		directStarted := time.Now()
		connection, err := d.protectedDialer().DialContext(ctx, "tcp", target)
		if err != nil {
			err = fmt.Errorf("dial local network directly: %w", err)
			report(d.reporter, "event=connection conn=%d mode=direct stage=tcp_connect result=failed reason=%s elapsed_ms=%d", connectionID, errorClass(err), time.Since(directStarted).Milliseconds())
			return nil, err
		}
		report(d.reporter, "event=connection conn=%d mode=direct stage=tcp_connect result=success elapsed_ms=%d", connectionID, time.Since(directStarted).Milliseconds())
		return &diagnosticConn{Conn: connection, connectionID: connectionID, reporter: d.reporter}, nil
	}
	if session := d.currentHTTP2Session(); session != nil {
		connection, err := d.openHTTP2Tunnel(ctx, session, target, connectionID, totalStarted, true)
		if err == nil {
			return connection, nil
		}
		if shouldFallbackToHTTP1(err) {
			d.disableHTTP2(session)
			report(d.reporter, "event=http2_session result=unsupported action=fallback_http1")
			return d.connectTarget(ctx, target)
		}
		d.invalidateHTTP2Session(session)
		report(d.reporter, "event=http2_session result=stale action=reconnect reason=%s", errorClass(err))
	}
	report(d.reporter, "event=connection conn=%d mode=proxy stage=tcp_connect result=started fingerprint=%s", connectionID, d.config.Profile)
	dialStarted := time.Now()
	raw, err := d.protectedDialer().DialContext(ctx, "tcp", d.config.address())
	if err != nil {
		recordConnectionOutcome(false)
		err = fmt.Errorf("dial HTTPS proxy: %w", err)
		report(d.reporter, "event=connection conn=%d mode=proxy stage=tcp_connect result=failed reason=%s elapsed_ms=%d", connectionID, errorClass(err), time.Since(dialStarted).Milliseconds())
		return nil, err
	}
	recordProxyLatency(time.Since(dialStarted))
	report(d.reporter, "event=connection conn=%d mode=proxy stage=tcp_connect result=success elapsed_ms=%d", connectionID, time.Since(dialStarted).Milliseconds())
	closeOnError := true
	defer func() {
		if closeOnError {
			_ = raw.Close()
		}
	}()

	hello, err := d.config.helloID()
	if err != nil {
		recordConnectionOutcome(false)
		report(d.reporter, "event=connection conn=%d mode=proxy stage=fingerprint result=failed reason=invalid_configuration", connectionID)
		return nil, err
	}
	uconn := tls.UClient(raw, &tls.Config{
		ServerName:         d.config.Host,
		MinVersion:         tls.VersionTLS12,
		InsecureSkipVerify: d.config.AllowInvalidProxyCertificate,
		ClientSessionCache: d.clientSessionCache(),
	}, hello)
	if hello == tls.HelloCustom {
		if err := applyJA3(uconn, d.config.CustomJA3, d.config.Host); err != nil {
			recordConnectionOutcome(false)
			report(d.reporter, "event=connection conn=%d mode=proxy stage=fingerprint result=failed reason=invalid_ja3", connectionID)
			return nil, err
		}
	}
	if d.isHTTP2Disabled() {
		if err := forceHTTP11ALPN(uconn); err != nil {
			recordConnectionOutcome(false)
			return nil, fmt.Errorf("configure HTTP/1.1 ALPN fallback: %w", err)
		}
	}
	tlsStarted := time.Now()
	handshakeContext, cancelHandshake := context.WithTimeout(ctx, 15*time.Second)
	err = uconn.HandshakeContext(handshakeContext)
	cancelHandshake()
	if err != nil {
		recordConnectionOutcome(false)
		reason := errorClass(err)
		report(d.reporter, "event=connection conn=%d mode=proxy stage=tls_handshake result=failed reason=%s elapsed_ms=%d dpi_hint=%s fingerprint=%s", connectionID, reason, time.Since(tlsStarted).Milliseconds(), tlsInterferenceHint(reason), d.config.Profile)
		err = fmt.Errorf("TLS handshake with proxy: %w", err)
		return nil, err
	}
	tlsState := uconn.ConnectionState()
	report(d.reporter, "event=connection conn=%d mode=proxy stage=tls_handshake result=success elapsed_ms=%d version=0x%04x cipher=0x%04x alpn=%q certificates=%d", connectionID, time.Since(tlsStarted).Milliseconds(), tlsState.Version, tlsState.CipherSuite, tlsState.NegotiatedProtocol, len(tlsState.PeerCertificates))
	h2Negotiated := tlsState.NegotiatedProtocol == "h2"
	report(d.reporter, "event=transport_capability transport=https_proxy tls_version=0x%04x outer_alpn=%s h2_negotiated=%t h2_connect_supported=true connect_protocol=%s session_resumed=%t", tlsState.Version, normalizedALPN(tlsState.NegotiatedProtocol), h2Negotiated, connectProtocol(h2Negotiated), tlsState.DidResume)
	if h2Negotiated {
		session, sessionErr := newHTTP2ConnectSession(uconn)
		if sessionErr != nil {
			recordConnectionOutcome(false)
			return nil, fmt.Errorf("initialize HTTP/2 proxy session: %w", sessionErr)
		}
		closeOnError = false // The HTTP/2 session now owns the outer TLS connection.
		session = d.installHTTP2Session(session)
		connection, connectErr := d.openHTTP2Tunnel(ctx, session, target, connectionID, totalStarted, false)
		if shouldFallbackToHTTP1(connectErr) {
			d.disableHTTP2(session)
			report(d.reporter, "event=http2_session result=unsupported action=fallback_http1")
			return d.connectTarget(ctx, target)
		}
		return connection, connectErr
	}
	deadline := time.Now().Add(15 * time.Second)
	if contextDeadline, ok := ctx.Deadline(); ok && contextDeadline.Before(deadline) {
		deadline = contextDeadline
	}
	if err := uconn.SetDeadline(deadline); err != nil {
		return nil, fmt.Errorf("set CONNECT deadline: %w", err)
	}

	auth := base64.StdEncoding.EncodeToString([]byte(d.config.Username + ":" + d.config.Password))
	if _, err := fmt.Fprintf(uconn, "CONNECT %s HTTP/1.1\r\nHost: %s\r\nProxy-Authorization: Basic %s\r\nProxy-Connection: Keep-Alive\r\n\r\n", target, target, auth); err != nil {
		recordConnectionOutcome(false)
		err = fmt.Errorf("write CONNECT: %w", err)
		report(d.reporter, "event=connection conn=%d mode=proxy stage=connect_write result=failed reason=%s", connectionID, errorClass(err))
		return nil, err
	}
	// A peer-controlled CONNECT header must not be allowed to grow the process heap
	// without bound. Any tunneled bytes already buffered after the header are retained.
	reader := bufio.NewReader(&limitedHeaderReader{reader: uconn, remaining: 64 * 1024})
	response, err := http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
	if err != nil {
		recordConnectionOutcome(false)
		err = fmt.Errorf("read CONNECT response: %w", err)
		reason := errorClass(err)
		report(d.reporter, "event=connection conn=%d mode=proxy stage=connect_response result=failed reason=%s dpi_hint=%s", connectionID, reason, tlsInterferenceHint(reason))
		return nil, err
	}
	if response.StatusCode != http.StatusOK {
		recordConnectionOutcome(false)
		_ = response.Body.Close()
		err = fmt.Errorf("proxy CONNECT returned %s", response.Status)
		report(d.reporter, "event=connection conn=%d mode=proxy stage=connect_response result=rejected status=%d status_class=%dxx", connectionID, response.StatusCode, response.StatusCode/100)
		return nil, err
	}
	if err := uconn.SetDeadline(time.Time{}); err != nil {
		return nil, fmt.Errorf("clear CONNECT deadline: %w", err)
	}
	recordConnectionOutcome(true)
	report(d.reporter, "event=connection conn=%d mode=proxy stage=tunnel result=established total_ms=%d", connectionID, time.Since(totalStarted).Milliseconds())
	closeOnError = false
	var connection net.Conn = uconn
	if reader.Buffered() > 0 {
		connection = &bufferedConn{Conn: uconn, reader: reader}
	}
	return &diagnosticConn{Conn: connection, connectionID: connectionID, reporter: d.reporter}, nil
}

type http2ConnectStatusError struct{ status int }

func (e *http2ConnectStatusError) Error() string {
	return fmt.Sprintf("HTTP/2 proxy CONNECT returned status %d", e.status)
}

func shouldFallbackToHTTP1(err error) bool {
	var statusError *http2ConnectStatusError
	return errors.As(err, &statusError) && (statusError.status == http.StatusMethodNotAllowed || statusError.status == http.StatusNotImplemented)
}

func connectProtocol(h2 bool) string {
	if h2 {
		return "http2"
	}
	return "http1_1"
}

func (d *httpsConnectDialer) currentHTTP2Session() *http2ConnectSession {
	d.cacheMu.Lock()
	defer d.cacheMu.Unlock()
	if d.h2Session != nil && d.h2Session.canTakeRequest() {
		return d.h2Session
	}
	return nil
}

func (d *httpsConnectDialer) installHTTP2Session(candidate *http2ConnectSession) *http2ConnectSession {
	d.cacheMu.Lock()
	defer d.cacheMu.Unlock()
	if d.h2Session != nil && d.h2Session.canTakeRequest() {
		_ = candidate.close()
		return d.h2Session
	}
	if d.h2Session != nil {
		_ = d.h2Session.close()
	}
	d.h2Session = candidate
	report(d.reporter, "event=http2_session result=established multiplexed=true")
	return candidate
}

func (d *httpsConnectDialer) invalidateHTTP2Session(session *http2ConnectSession) {
	d.cacheMu.Lock()
	defer d.cacheMu.Unlock()
	if d.h2Session == session {
		d.h2Session = nil
		_ = session.close()
	}
}

func (d *httpsConnectDialer) disableHTTP2(session *http2ConnectSession) {
	d.cacheMu.Lock()
	defer d.cacheMu.Unlock()
	d.h2Disabled = true
	if d.h2Session == session {
		d.h2Session = nil
		_ = session.close()
	}
}

func (d *httpsConnectDialer) isHTTP2Disabled() bool {
	d.cacheMu.Lock()
	defer d.cacheMu.Unlock()
	return d.h2Disabled
}

func (d *httpsConnectDialer) openHTTP2Tunnel(ctx context.Context, session *http2ConnectSession, target string, connectionID uint64, totalStarted time.Time, reused bool) (net.Conn, error) {
	auth := "Basic " + base64.StdEncoding.EncodeToString([]byte(d.config.Username+":"+d.config.Password))
	started := time.Now()
	tunnel, status, err := session.openTunnel(ctx, target, auth)
	if err != nil {
		recordConnectionOutcome(false)
		if status != 0 {
			report(d.reporter, "event=connection conn=%d mode=proxy protocol=http2 stage=connect_response result=rejected status=%d status_class=%dxx reused_session=%t", connectionID, status, status/100, reused)
			return nil, &http2ConnectStatusError{status: status}
		}
		report(d.reporter, "event=connection conn=%d mode=proxy protocol=http2 stage=connect_response result=failed reason=%s reused_session=%t", connectionID, errorClass(err), reused)
		return nil, fmt.Errorf("HTTP/2 proxy CONNECT: %w", err)
	}
	recordProxyLatency(time.Since(started))
	recordConnectionOutcome(true)
	report(d.reporter, "event=connection conn=%d mode=proxy protocol=http2 stage=tunnel result=established stream_multiplexed=true reused_session=%t total_ms=%d", connectionID, reused, time.Since(totalStarted).Milliseconds())
	return &diagnosticConn{Conn: tunnel, connectionID: connectionID, reporter: d.reporter}, nil
}

func normalizedALPN(value string) string {
	if value == "" {
		return "none"
	}
	return strings.ReplaceAll(value, " ", "_")
}

func forceHTTP11ALPN(connection *tls.UConn) error {
	if err := connection.BuildHandshakeState(); err != nil {
		return err
	}
	for _, extension := range connection.Extensions {
		if alpn, ok := extension.(*tls.ALPNExtension); ok {
			alpn.AlpnProtocols = []string{"http/1.1"}
			return nil
		}
	}
	connection.Extensions = append(connection.Extensions, &tls.ALPNExtension{AlpnProtocols: []string{"http/1.1"}})
	return nil
}

func (d *httpsConnectDialer) clientSessionCache() tls.ClientSessionCache {
	d.cacheMu.Lock()
	defer d.cacheMu.Unlock()
	if d.sessionCache == nil {
		d.sessionCache = tls.NewLRUClientSessionCache(32)
	}
	return d.sessionCache
}

func (d *httpsConnectDialer) protectedDialer() *net.Dialer {
	return &net.Dialer{
		Timeout:   15 * time.Second,
		KeepAlive: 30 * time.Second,
		Control: func(_, _ string, raw syscall.RawConn) error {
			var protectErr error
			if err := raw.Control(func(fd uintptr) {
				if !d.protector.Protect(int(fd)) {
					protectErr = errors.New("VpnService.protect rejected upstream socket")
				}
			}); err != nil {
				return err
			}
			return protectErr
		},
	}
}

func isLocalNetworkTarget(target string) bool {
	host, _, err := net.SplitHostPort(target)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	return ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast()
}

func (d *httpsConnectDialer) DialUDP(metadata *M.Metadata) (net.PacketConn, error) {
	if metadata.DstPort != 53 {
		return nil, errUDPBlocked
	}
	d.cacheMu.Lock()
	if d.dohClient == nil {
		d.dohClient = newDoHHTTPClient(d.connectTarget)
	}
	if d.dohInFlight == nil {
		d.dohInFlight = make(chan struct{}, 8)
	}
	client := d.dohClient
	limiter := d.dohInFlight
	d.cacheMu.Unlock()
	return newDoHPacketConnWithClient(d.config, d.reporter, d.connectTarget, d.config.DoHURL, client, limiter), nil
}

type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *bufferedConn) Read(p []byte) (int, error) { return c.reader.Read(p) }

type limitedHeaderReader struct {
	reader    io.Reader
	remaining int
	window    uint32
	done      bool
}

func (r *limitedHeaderReader) Read(p []byte) (int, error) {
	if r.done {
		return r.reader.Read(p)
	}
	if r.remaining <= 0 {
		return 0, errors.New("proxy CONNECT response headers exceed 64 KB")
	}
	if len(p) > r.remaining {
		p = p[:r.remaining]
	}
	n, err := r.reader.Read(p)
	r.remaining -= n
	for _, value := range p[:n] {
		r.window = r.window<<8 | uint32(value)
		if r.window == 0x0d0a0d0a {
			r.done = true
			break
		}
	}
	return n, err
}

type diagnosticConn struct {
	net.Conn
	connectionID uint64
	reporter     Reporter
	once         sync.Once
	transferred  atomic.Uint64
}

type slotConn struct {
	net.Conn
	release func()
	once    sync.Once
}

func (c *slotConn) Close() error {
	err := c.Conn.Close()
	c.once.Do(c.release)
	return err
}

func (c *diagnosticConn) reportError(operation string, err error) {
	if err != nil && !errors.Is(err, io.EOF) && !errors.Is(err, net.ErrClosed) {
		c.once.Do(func() {
			reason := errorClass(err)
			report(c.reporter, "event=connection conn=%d stage=tunnel_io result=failed operation=%s reason=%s transferred_bytes=%d dpi_hint=%s", c.connectionID, operation, reason, c.transferred.Load(), tlsInterferenceHint(reason))
		})
	}
}

func (c *diagnosticConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if n > 0 {
		telemetry.downloadBytes.Add(uint64(n))
		c.transferred.Add(uint64(n))
	}
	c.reportError("read", err)
	return n, err
}

func (c *diagnosticConn) Write(p []byte) (int, error) {
	n, err := c.Conn.Write(p)
	if n > 0 {
		telemetry.uploadBytes.Add(uint64(n))
		c.transferred.Add(uint64(n))
	}
	c.reportError("write", err)
	return n, err
}
