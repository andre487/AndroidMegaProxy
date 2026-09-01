package mobile

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"sync"
	"time"

	"golang.org/x/net/http2"
)

type http2ClientConn interface {
	RoundTrip(*http.Request) (*http.Response, error)
	CanTakeNewRequest() bool
	Close() error
}

type http2ConnectSession struct {
	client http2ClientConn
	raw    net.Conn
}

func newHTTP2ConnectSession(raw net.Conn) (*http2ConnectSession, error) {
	transport := &http2.Transport{
		ReadIdleTimeout: 45 * time.Second,
		PingTimeout:     10 * time.Second,
	}
	client, err := transport.NewClientConn(raw)
	if err != nil {
		return nil, err
	}
	return &http2ConnectSession{client: client, raw: raw}, nil
}

func (s *http2ConnectSession) canTakeRequest() bool {
	return s != nil && s.client != nil && s.client.CanTakeNewRequest()
}

func (s *http2ConnectSession) close() error {
	if s == nil {
		return nil
	}
	if s.client != nil {
		_ = s.client.Close()
	}
	if s.raw != nil {
		return s.raw.Close()
	}
	return nil
}

func (s *http2ConnectSession) openTunnel(ctx context.Context, target, authorization string) (net.Conn, int, error) {
	reader, writer := io.Pipe()
	streamContext, cancelStream := context.WithCancel(context.Background())
	request := &http.Request{
		Method:        http.MethodConnect,
		URL:           &url.URL{Scheme: "https", Host: target},
		Host:          target,
		Header:        make(http.Header),
		Body:          reader,
		ContentLength: -1,
	}
	request = request.WithContext(streamContext)
	request.Header.Set("Proxy-Authorization", authorization)

	type result struct {
		response *http.Response
		err      error
	}
	resultChannel := make(chan result, 1)
	go func() {
		response, err := s.client.RoundTrip(request)
		select {
		case resultChannel <- result{response: response, err: err}:
		case <-streamContext.Done():
			if response != nil {
				_ = response.Body.Close()
			}
		}
	}()

	var outcome result
	select {
	case outcome = <-resultChannel:
	case <-ctx.Done():
		cancelStream()
		_ = writer.CloseWithError(ctx.Err())
		_ = reader.CloseWithError(ctx.Err())
		return nil, 0, ctx.Err()
	}
	if outcome.err != nil {
		cancelStream()
		_ = writer.CloseWithError(outcome.err)
		_ = reader.CloseWithError(outcome.err)
		return nil, 0, outcome.err
	}
	if outcome.response.StatusCode != http.StatusOK {
		_ = outcome.response.Body.Close()
		cancelStream()
		_ = writer.Close()
		_ = reader.Close()
		return nil, outcome.response.StatusCode, errors.New("HTTP/2 proxy rejected CONNECT")
	}
	return newHTTP2StreamConn(s.raw, outcome.response.Body, writer, cancelStream), outcome.response.StatusCode, nil
}

// http2StreamConn exposes one HTTP/2 CONNECT stream as a net.Conn. A deadline
// closes only this stream, never the shared outer TLS connection.
type http2StreamConn struct {
	raw          net.Conn
	reader       io.ReadCloser
	writer       *io.PipeWriter
	cancel       context.CancelFunc
	closeOnce    sync.Once
	deadlineMu   sync.Mutex
	readTimer    *time.Timer
	writeTimer   *time.Timer
	readExpired  bool
	writeExpired bool
}

func newHTTP2StreamConn(raw net.Conn, reader io.ReadCloser, writer *io.PipeWriter, cancel context.CancelFunc) *http2StreamConn {
	return &http2StreamConn{raw: raw, reader: reader, writer: writer, cancel: cancel}
}

func (c *http2StreamConn) Read(p []byte) (int, error) {
	n, err := c.reader.Read(p)
	c.deadlineMu.Lock()
	expired := c.readExpired
	c.deadlineMu.Unlock()
	if expired && err != nil {
		return n, os.ErrDeadlineExceeded
	}
	return n, err
}

func (c *http2StreamConn) Write(p []byte) (int, error) {
	n, err := c.writer.Write(p)
	c.deadlineMu.Lock()
	expired := c.writeExpired
	c.deadlineMu.Unlock()
	if expired && err != nil {
		return n, os.ErrDeadlineExceeded
	}
	return n, err
}

func (c *http2StreamConn) Close() error {
	var closeErr error
	c.closeOnce.Do(func() {
		c.deadlineMu.Lock()
		if c.readTimer != nil {
			c.readTimer.Stop()
		}
		if c.writeTimer != nil {
			c.writeTimer.Stop()
		}
		c.deadlineMu.Unlock()
		c.cancel()
		_ = c.writer.Close()
		closeErr = c.reader.Close()
	})
	return closeErr
}

func (c *http2StreamConn) LocalAddr() net.Addr  { return c.raw.LocalAddr() }
func (c *http2StreamConn) RemoteAddr() net.Addr { return c.raw.RemoteAddr() }

func (c *http2StreamConn) SetDeadline(deadline time.Time) error {
	c.setReadDeadline(deadline)
	c.setWriteDeadline(deadline)
	return nil
}

func (c *http2StreamConn) SetReadDeadline(deadline time.Time) error {
	c.setReadDeadline(deadline)
	return nil
}

func (c *http2StreamConn) SetWriteDeadline(deadline time.Time) error {
	c.setWriteDeadline(deadline)
	return nil
}

func (c *http2StreamConn) setReadDeadline(deadline time.Time) {
	c.deadlineMu.Lock()
	defer c.deadlineMu.Unlock()
	c.readExpired = false
	if c.readTimer != nil {
		c.readTimer.Stop()
		c.readTimer = nil
	}
	if !deadline.IsZero() {
		c.readTimer = time.AfterFunc(time.Until(deadline), func() {
			c.deadlineMu.Lock()
			c.readExpired = true
			c.deadlineMu.Unlock()
			_ = c.Close()
		})
	}
}

func (c *http2StreamConn) setWriteDeadline(deadline time.Time) {
	c.deadlineMu.Lock()
	defer c.deadlineMu.Unlock()
	c.writeExpired = false
	if c.writeTimer != nil {
		c.writeTimer.Stop()
		c.writeTimer = nil
	}
	if !deadline.IsZero() {
		c.writeTimer = time.AfterFunc(time.Until(deadline), func() {
			c.deadlineMu.Lock()
			c.writeExpired = true
			c.deadlineMu.Unlock()
			_ = c.Close()
		})
	}
}
