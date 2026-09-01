package mobile

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

func validateDoHURL(raw string) error {
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.Path == "" {
		return errors.New("invalid HTTPS DoH URL")
	}
	if u.User != nil || u.Fragment != "" {
		return errors.New("DoH URL must not contain credentials or fragment")
	}
	return nil
}

type dnsReply struct {
	payload []byte
	addr    net.Addr
	err     error
}

type dohPacketConn struct {
	config       config
	reporter     Reporter
	connect      func(context.Context, string) (net.Conn, error)
	url          string
	replies      chan dnsReply
	inFlight     chan struct{}
	closed       chan struct{}
	closeOnce    sync.Once
	deadlineMu   sync.Mutex
	readDeadline time.Time
	client       *http.Client
	context      context.Context
	cancel       context.CancelFunc
}

func newDoHPacketConn(c config, reporter Reporter, connect func(context.Context, string) (net.Conn, error), endpoint string) *dohPacketConn {
	return newDoHPacketConnWithClient(c, reporter, connect, endpoint, newDoHHTTPClient(connect), nil)
}

func newDoHHTTPClient(connect func(context.Context, string) (net.Conn, error)) *http.Client {
	transport := &http.Transport{
		Proxy:                  nil,
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
	return &dohPacketConn{
		config: c, reporter: reporter, connect: connect, url: endpoint, replies: make(chan dnsReply, 16), closed: make(chan struct{}),
		inFlight: limiter, client: client, context: ctx, cancel: cancel,
	}
}

func (c *dohPacketConn) WriteTo(payload []byte, addr net.Addr) (int, error) {
	select {
	case <-c.closed:
		return 0, net.ErrClosed
	default:
	}
	if !c.config.AllowIPv6 {
		response, isAAAA, err := emptyAAAAResponse(payload)
		if err != nil {
			return 0, err
		}
		if isAAAA {
			report(c.reporter, "event=doh result=suppressed query_type=aaaa reason=ipv4_only")
			c.deliver(dnsReply{payload: response, addr: addr})
			return len(payload), nil
		}
	}
	query := append([]byte(nil), payload...)
	select {
	case c.inFlight <- struct{}{}:
	case <-c.closed:
		return 0, net.ErrClosed
	case <-c.context.Done():
		return 0, c.context.Err()
	}
	go func() {
		defer func() { <-c.inFlight }()
		req, err := http.NewRequestWithContext(c.context, http.MethodPost, c.url, bytes.NewReader(query))
		if err == nil {
			req.Header.Set("Accept", "application/dns-message")
			req.Header.Set("Content-Type", "application/dns-message")
			var response *http.Response
			response, err = c.client.Do(req)
			if err == nil {
				defer response.Body.Close()
				if response.StatusCode != http.StatusOK {
					err = fmt.Errorf("DoH returned %s", response.Status)
				} else if !strings.HasPrefix(response.Header.Get("Content-Type"), "application/dns-message") {
					err = errors.New("DoH returned an unexpected content type")
				} else {
					var body []byte
					body, err = io.ReadAll(io.LimitReader(response.Body, 65536))
					if err == nil {
						c.deliver(dnsReply{payload: body, addr: addr})
					}
				}
			}
		}
		if err != nil {
			report(c.reporter, "event=doh result=failed reason=%s", errorClass(err))
			c.deliver(dnsReply{addr: addr, err: err})
		} else {
			report(c.reporter, "event=doh result=success")
		}
	}()
	return len(payload), nil
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
	}
}

func (c *dohPacketConn) ReadFrom(buffer []byte) (int, net.Addr, error) {
	c.deadlineMu.Lock()
	deadline := c.readDeadline
	c.deadlineMu.Unlock()
	var timer <-chan time.Time
	if !deadline.IsZero() {
		duration := time.Until(deadline)
		if duration <= 0 {
			return 0, nil, timeoutError{}
		}
		t := time.NewTimer(duration)
		defer t.Stop()
		timer = t.C
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
	case <-timer:
		return 0, nil, timeoutError{}
	case <-c.closed:
		return 0, nil, net.ErrClosed
	}
}

func (c *dohPacketConn) Close() error {
	c.closeOnce.Do(func() {
		close(c.closed)
		c.cancel()
	})
	return nil
}
func (c *dohPacketConn) LocalAddr() net.Addr { return dnsAddr("megaproxy-doh") }
func (c *dohPacketConn) SetDeadline(t time.Time) error {
	c.deadlineMu.Lock()
	c.readDeadline = t
	c.deadlineMu.Unlock()
	return nil
}
func (c *dohPacketConn) SetReadDeadline(t time.Time) error { return c.SetDeadline(t) }
func (c *dohPacketConn) SetWriteDeadline(time.Time) error  { return nil }

type dnsAddr string

func (a dnsAddr) Network() string { return "udp" }
func (a dnsAddr) String() string  { return string(a) }

type timeoutError struct{}

func (timeoutError) Error() string   { return "DNS read deadline exceeded" }
func (timeoutError) Timeout() bool   { return true }
func (timeoutError) Temporary() bool { return true }
