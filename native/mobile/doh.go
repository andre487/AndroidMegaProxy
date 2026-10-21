package mobile

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

var errNotDoH = errors.New("HTTPS endpoint did not return a DNS-over-HTTPS response")

func validateDoHURL(raw string) error {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || strings.ContainsAny(raw, " \t\r\n") {
		return errors.New("invalid HTTPS DoH URL")
	}
	if strings.Contains(u.Hostname(), ":") && !strings.HasPrefix(u.Host, "[") {
		return errors.New("DoH IPv6 address must be enclosed in brackets")
	}
	if u.User != nil || strings.Contains(raw, "#") {
		return errors.New("DoH URL must not contain credentials or fragment")
	}
	if port := u.Port(); port != "" {
		value, parseErr := strconv.Atoi(port)
		if parseErr != nil || value < 1 || value > 65535 {
			return errors.New("DoH URL contains an invalid port")
		}
	}
	return nil
}

type dnsReply struct {
	payload []byte
	addr    net.Addr
	err     error
}

type dohPacketConn struct {
	onClose       func()
	connectUDP    func(context.Context, string) (net.PacketConn, error)
	config        config
	reporter      Reporter
	connect       func(context.Context, string) (net.Conn, error)
	endpoints     []string
	replies       chan dnsReply
	inFlight      chan struct{}
	closed        chan struct{}
	closeOnce     sync.Once
	readDeadline  packetDeadline
	writeDeadline packetDeadline
	client        *http.Client
	context       context.Context
	cancel        context.CancelFunc
}

func newDoHPacketConn(c config, reporter Reporter, connect func(context.Context, string) (net.Conn, error), endpoint string) *dohPacketConn {
	return newDoHPacketConnWithClient(c, reporter, connect, endpoint, newDoHHTTPClient(connect, c.AllowInvalidDoHCertificate), nil)
}

func newDoHHTTPClient(connect func(context.Context, string) (net.Conn, error), allowInvalid ...bool) *http.Client {
	transport := &http.Transport{
		Proxy:                  nil,
		TLSClientConfig:        &tls.Config{InsecureSkipVerify: len(allowInvalid) > 0 && allowInvalid[0]}, // Explicit local DoH-only opt-in.
		ForceAttemptHTTP2:      true,
		TLSHandshakeTimeout:    15 * time.Second,
		MaxConnsPerHost:        2,
		MaxIdleConnsPerHost:    2,
		IdleConnTimeout:        90 * time.Second,
		MaxResponseHeaderBytes: 64 * 1024,
		DialContext: func(ctx context.Context, _, address string) (net.Conn, error) {
			return connect(ctx, address)
		},
	}
	return &http.Client{Transport: transport, Timeout: 20 * time.Second}
}

func newDoHPacketConnWithClient(c config, reporter Reporter, connect func(context.Context, string) (net.Conn, error), endpoint string, client *http.Client, limiter chan struct{}) *dohPacketConn {
	ctx, cancel := context.WithCancel(context.Background())
	if limiter == nil {
		limiter = make(chan struct{}, 8)
	}
	endpoints := []string{endpoint}
	for _, fallback := range c.DoHFallbackURLs {
		if c.plainDNS() {
			break
		}
		if fallback != endpoint {
			endpoints = append(endpoints, fallback)
		}
	}
	return &dohPacketConn{
		config: c, reporter: reporter, connect: connect, endpoints: endpoints, replies: make(chan dnsReply, 16), closed: make(chan struct{}),
		inFlight: limiter, client: client, context: ctx, cancel: cancel,
	}
}

func (c *dohPacketConn) WriteTo(payload []byte, addr net.Addr) (int, error) {
	if len(payload) < 12 || len(payload) > 65535 {
		return 0, errors.New("invalid DNS packet size")
	}
	deadline := c.writeDeadline.wait()
	select {
	case <-c.closed:
		return 0, net.ErrClosed
	case <-deadline:
		return 0, c.deadlineError()
	default:
	}
	if !c.config.AllowIPv6 {
		response, isAAAA, err := emptyAAAAResponse(payload)
		if err != nil {
			return 0, err
		}
		if isAAAA {
			report(c.reporter, "event=%s result=suppressed query_type=aaaa reason=ipv4_only", c.eventName())
			c.deliver(dnsReply{payload: response, addr: addr})
			return len(payload), nil
		}
	}
	select {
	case c.inFlight <- struct{}{}:
	case <-deadline:
		return 0, c.deadlineError()
	case <-c.closed:
		return 0, net.ErrClosed
	case <-c.context.Done():
		return 0, c.context.Err()
	}
	// Allocate only after admission, so waiting writers cannot retain extra packet copies.
	query := append([]byte(nil), payload...)
	go func() {
		defer func() { <-c.inFlight }()
		body, err := raceFirst(c.context, len(c.endpoints), func(ctx context.Context, index int) ([]byte, error) {
			return c.queryEndpointContext(ctx, c.endpoints[index], query)
		}, nil)
		if err != nil {
			c.deliver(dnsReply{addr: addr, err: fmt.Errorf("all DNS providers failed: %w", err)})
			report(c.reporter, "event=%s result=failed reason=%s", c.eventName(), errorClass(err))
			return
		}
		c.deliver(dnsReply{payload: body, addr: addr})
		report(c.reporter, "event=%s result=success", c.eventName())
	}()
	return len(payload), nil
}

func (c *dohPacketConn) queryEndpoint(endpoint string, query []byte) ([]byte, error) {
	return c.queryEndpointContext(c.context, endpoint, query)
}

func (c *dohPacketConn) queryEndpointContext(ctx context.Context, endpoint string, query []byte) ([]byte, error) {
	requestContext, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	if u, parseErr := url.Parse(endpoint); parseErr == nil && (u.Scheme == "tcp" || u.Scheme == "udp") {
		return c.queryPlainDNS(requestContext, endpoint, query)
	}
	req, err := http.NewRequestWithContext(requestContext, http.MethodPost, endpoint, bytes.NewReader(query))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/dns-message")
	req.Header.Set("Content-Type", "application/dns-message")
	response, err := c.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("DoH returned %s", response.Status)
	}
	mediaType, _, err := mime.ParseMediaType(response.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/dns-message" {
		return nil, errNotDoH
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 65537))
	if err != nil {
		return nil, err
	}
	if len(body) > 65536 {
		return nil, errors.New("DoH response exceeds 64 KB")
	}
	if !dnsReplyMatches(query, body) {
		return nil, errNotDoH
	}
	return body, nil
}

func emptyAAAAResponse(query []byte) ([]byte, bool, error) {
	if len(query) < 12 || binary.BigEndian.Uint16(query[4:6]) != 1 {
		return nil, false, nil
	}
	offset, err := skipDNSName(query, 12)
	if err != nil || offset+4 > len(query) {
		return nil, false, errors.New("malformed DNS query")
	}
	if binary.BigEndian.Uint16(query[offset:offset+2]) != 28 {
		return nil, false, nil
	}
	response := append([]byte(nil), query[:offset+4]...)
	requestFlags := binary.BigEndian.Uint16(query[2:4])
	binary.BigEndian.PutUint16(response[2:4], 0x8000|(requestFlags&0x0100)|0x0080)
	binary.BigEndian.PutUint16(response[6:8], 0)
	binary.BigEndian.PutUint16(response[8:10], 0)
	binary.BigEndian.PutUint16(response[10:12], 0)
	return response, true, nil
}

func (c *dohPacketConn) deliver(reply dnsReply) {
	select {
	case c.replies <- reply:
	case <-c.closed:
	default:
		// UDP may drop packets. A stalled reader must not consume every shared query slot.
		report(c.reporter, "event=%s result=dropped reason=reply_queue_full", c.eventName())
	}
}

func (c *dohPacketConn) ReadFrom(buffer []byte) (int, net.Addr, error) {
	deadline := c.readDeadline.wait()
	select {
	case <-deadline:
		return 0, nil, c.deadlineError()
	case <-c.closed:
		return 0, nil, net.ErrClosed
	default:
	}
	select {
	case reply := <-c.replies:
		if reply.err != nil {
			return 0, reply.addr, reply.err
		}
		if len(reply.payload) > len(buffer) {
			return 0, reply.addr, io.ErrShortBuffer
		}
		return copy(buffer, reply.payload), reply.addr, nil
	case <-deadline:
		return 0, nil, c.deadlineError()
	case <-c.closed:
		return 0, nil, net.ErrClosed
	}
}

// Close also wakes deadline waiters; report closure instead of a random timeout.
func (c *dohPacketConn) deadlineError() error {
	select {
	case <-c.closed:
		return net.ErrClosed
	default:
		return timeoutError{}
	}
}

func (c *dohPacketConn) Close() error {
	c.closeOnce.Do(func() {
		close(c.closed)
		c.cancel()
		c.readDeadline.stop()
		c.writeDeadline.stop()
		if c.onClose != nil {
			c.onClose()
		}
	})
	return nil
}
func (c *dohPacketConn) LocalAddr() net.Addr { return dnsAddr("megaproxy-doh") }
func (c *dohPacketConn) SetDeadline(t time.Time) error {
	if err := c.SetReadDeadline(t); err != nil {
		return err
	}
	return c.SetWriteDeadline(t)
}
func (c *dohPacketConn) SetReadDeadline(t time.Time) error {
	select {
	case <-c.closed:
		return net.ErrClosed
	default:
	}
	c.readDeadline.set(t)
	return nil
}
func (c *dohPacketConn) SetWriteDeadline(t time.Time) error {
	select {
	case <-c.closed:
		return net.ErrClosed
	default:
	}
	c.writeDeadline.set(t)
	return nil
}

type dnsAddr string

func (a dnsAddr) Network() string { return "udp" }
func (a dnsAddr) String() string  { return string(a) }

type timeoutError struct{}

func (timeoutError) Error() string   { return "DNS operation deadline exceeded" }
func (timeoutError) Timeout() bool   { return true }
func (timeoutError) Temporary() bool { return true }

func (c *dohPacketConn) eventName() string {
	if c.config.plainDNS() {
		return "dns"
	}
	return "doh"
}
