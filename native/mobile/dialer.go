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
}

func (d *httpsConnectDialer) Close() error {
	d.cacheMu.Lock()
	defer d.cacheMu.Unlock()
	if d.dohClient != nil {
		d.dohClient.CloseIdleConnections()
		d.dohClient = nil
	}
	return nil
}

func (d *httpsConnectDialer) DialContext(ctx context.Context, metadata *M.Metadata) (net.Conn, error) {
	return d.connectTarget(ctx, metadata.DestinationAddress())
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
	tlsStarted := time.Now()
	if err := uconn.HandshakeContext(ctx); err != nil {
		recordConnectionOutcome(false)
		reason := errorClass(err)
		report(d.reporter, "event=connection conn=%d mode=proxy stage=tls_handshake result=failed reason=%s elapsed_ms=%d dpi_hint=%s fingerprint=%s", connectionID, reason, time.Since(tlsStarted).Milliseconds(), tlsInterferenceHint(reason), d.config.Profile)
		err = fmt.Errorf("TLS handshake with proxy: %w", err)
		return nil, err
	}
	tlsState := uconn.ConnectionState()
	report(d.reporter, "event=connection conn=%d mode=proxy stage=tls_handshake result=success elapsed_ms=%d version=0x%04x cipher=0x%04x alpn=%q certificates=%d", connectionID, time.Since(tlsStarted).Milliseconds(), tlsState.Version, tlsState.CipherSuite, tlsState.NegotiatedProtocol, len(tlsState.PeerCertificates))
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
	reader := bufio.NewReader(uconn)
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
	client := d.dohClient
	d.cacheMu.Unlock()
	return newDoHPacketConnWithClient(d.config, d.reporter, d.connectTarget, d.config.DoHURL, client), nil
}

type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *bufferedConn) Read(p []byte) (int, error) { return c.reader.Read(p) }

type diagnosticConn struct {
	net.Conn
	connectionID uint64
	reporter     Reporter
	once         sync.Once
	transferred  atomic.Uint64
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
