package mobile

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/net/http2"
)

func TestHTTP2ConnectSessionMultiplexesStreams(t *testing.T) {
	var mu sync.Mutex
	authorities := make([]string, 0, 2)
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect {
			t.Errorf("method = %q, want CONNECT", r.Method)
			return
		}
		if r.Header.Get("Proxy-Authorization") != "Basic test" {
			t.Errorf("authorization was not forwarded")
		}
		mu.Lock()
		authorities = append(authorities, r.Host)
		mu.Unlock()
		controller := http.NewResponseController(w)
		if err := controller.EnableFullDuplex(); err != nil {
			t.Errorf("enable full duplex: %v", err)
			return
		}
		w.WriteHeader(http.StatusOK)
		_ = controller.Flush()
		buffer := make([]byte, 1024)
		for {
			count, err := r.Body.Read(buffer)
			if count > 0 {
				_, _ = w.Write(buffer[:count])
				_ = controller.Flush()
			}
			if err != nil {
				return
			}
		}
	}))
	server.EnableHTTP2 = true
	server.StartTLS()
	defer server.Close()

	raw, err := tls.Dial("tcp", server.Listener.Addr().String(), &tls.Config{
		InsecureSkipVerify: true, // Test server certificate.
		NextProtos:         []string{"h2"},
	})
	if err != nil {
		t.Fatal(err)
	}
	session, err := newHTTP2ConnectSession(raw)
	if err != nil {
		t.Fatal(err)
	}
	defer session.close()

	for index := 0; index < 2; index++ {
		target := fmt.Sprintf("target-%d.example:443", index)
		stream, status, err := session.openTunnel(context.Background(), target, "Basic test")
		if err != nil || status != http.StatusOK {
			t.Fatalf("open stream %d: status=%d err=%v", index, status, err)
		}
		payload := []byte(fmt.Sprintf("stream-%d", index))
		if _, err := stream.Write(payload); err != nil {
			t.Fatal(err)
		}
		if err := stream.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
			t.Fatal(err)
		}
		response := make([]byte, len(payload))
		if _, err := io.ReadFull(stream, response); err != nil {
			t.Fatal(err)
		}
		if string(response) != string(payload) {
			t.Fatalf("response = %q, want %q", response, payload)
		}
		_ = stream.Close()
	}

	mu.Lock()
	defer mu.Unlock()
	if len(authorities) != 2 || authorities[0] != "target-0.example:443" || authorities[1] != "target-1.example:443" {
		t.Fatalf("unexpected CONNECT authorities: %#v", authorities)
	}
}

func TestHTTP2StreamDeadlineDoesNotCloseSharedConnection(t *testing.T) {
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	reader, writer := io.Pipe()
	stream := newHTTP2StreamConn(left, io.NopCloser(reader), writer, func() {})
	if err := stream.SetReadDeadline(time.Now().Add(10 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	buffer := make([]byte, 1)
	if _, err := stream.Read(buffer); err == nil || !isTimeout(err) {
		t.Fatalf("Read error = %v, want timeout", err)
	}
	rawRead := make(chan error, 1)
	go func() {
		_, err := left.Read(buffer)
		rawRead <- err
	}()
	if _, err := right.Write([]byte{1}); err != nil {
		t.Fatalf("shared raw connection was closed by stream deadline: %v", err)
	}
	if err := <-rawRead; err != nil {
		t.Fatalf("shared raw connection read failed: %v", err)
	}
}

func isTimeout(err error) bool {
	value, ok := err.(interface{ Timeout() bool })
	return ok && value.Timeout()
}

type rejectedHTTP2Client struct{ closed atomic.Bool }

func (c *rejectedHTTP2Client) CanTakeNewRequest() bool { return !c.closed.Load() }
func (c *rejectedHTTP2Client) Close() error            { c.closed.Store(true); return nil }
func (c *rejectedHTTP2Client) State() http2.ClientConnState {
	return http2.ClientConnState{Closed: c.closed.Load()}
}
func (c *rejectedHTTP2Client) Shutdown(context.Context) error { return c.Close() }
func (c *rejectedHTTP2Client) RoundTrip(r *http.Request) (*http.Response, error) {
	_ = r.Body.Close()
	return &http.Response{StatusCode: http.StatusBadGateway, Body: io.NopCloser(strings.NewReader(""))}, nil
}
func TestHTTP2RejectedTargetPreservesSession(t *testing.T) {
	client := &rejectedHTTP2Client{}
	session := &http2ConnectSession{client: client}
	d := &httpsConnectDialer{h2Session: session}
	_, err := d.connectTarget(context.Background(), "example.com:443")
	var rejected *http2ConnectStatusError
	if !errors.As(err, &rejected) || rejected.status != http.StatusBadGateway {
		t.Fatalf("error = %v", err)
	}
	if client.closed.Load() || d.currentHTTP2Session() != session {
		t.Fatal("target failure closed shared session")
	}
}

func TestSupersededHTTP2DeadlineCannotCloseStream(t *testing.T) {
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	reader, writer := io.Pipe()
	stream := newHTTP2StreamConn(left, reader, writer, func() {})
	defer stream.Close()
	_ = stream.SetReadDeadline(time.Now().Add(time.Hour))
	old := stream.readGeneration
	_ = stream.SetReadDeadline(time.Time{})
	stream.expireDeadline(true, old) // Timer callback already queued before Stop.
	if stream.closed || stream.readExpired {
		t.Fatal("old read timer expired a cleared deadline")
	}
	_ = stream.SetWriteDeadline(time.Now().Add(time.Hour))
	old = stream.writeGeneration
	_ = stream.SetWriteDeadline(time.Time{})
	stream.expireDeadline(false, old)
	if stream.closed || stream.writeExpired {
		t.Fatal("old write timer expired a cleared deadline")
	}
	_ = stream.Close()
	_ = stream.SetReadDeadline(time.Now().Add(time.Hour))
	if stream.readTimer != nil && !stream.readTimer.Stop() {
		t.Fatal("closed stream installed an active timer")
	}
}

func TestClosedDialerRejectsLateHTTP2Session(t *testing.T) {
	d := &httpsConnectDialer{}
	_ = d.Close()
	client := &rejectedHTTP2Client{}
	if session := d.installHTTP2Session(&http2ConnectSession{client: client}); session != nil || !client.closed.Load() {
		t.Fatal("late handshake resurrected a closed dialer")
	}
}

func TestHTTP2GoAwayPreservesActiveTunnelDuringReplacement(t *testing.T) {
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		_, _ = io.Copy(jumpFlushWriter{w}, r.Body)
	}))
	server.EnableHTTP2 = true
	server.StartTLS()
	defer server.Close()
	openSession := func(address string) *http2ConnectSession {
		t.Helper()
		raw, err := tls.Dial("tcp", address, &tls.Config{InsecureSkipVerify: true, NextProtos: []string{"h2"}})
		if err != nil {
			t.Fatal(err)
		}
		s, err := newHTTP2ConnectSession(raw)
		if err != nil {
			t.Fatal(err)
		}
		return s
	}
	old := openSession(server.Listener.Addr().String())
	d := &httpsConnectDialer{}
	defer d.Close()
	d.installHTTP2Session(old)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	stream, _, err := old.openTunnel(ctx, "old.example:443", "Basic test")
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Close()
	check := func(c net.Conn, value string) {
		t.Helper()
		_ = c.SetDeadline(time.Now().Add(time.Second))
		if _, err := c.Write([]byte(value)); err != nil {
			t.Fatal(err)
		}
		b := make([]byte, len(value))
		if _, err := io.ReadFull(c, b); err != nil || string(b) != value {
			t.Fatalf("active tunnel: %q %v", b, err)
		}
	}
	check(stream, "before")
	go func() { _ = server.Config.Shutdown(ctx) }()
	for old.canTakeRequest() {
		if ctx.Err() != nil {
			t.Fatal("GOAWAY was not received")
		}
		time.Sleep(time.Millisecond)
	}
	freshServer := jumpTestProxy(t, true, "new.example:443", "exit:test", "", false)
	fresh := openSession(freshServer.Listener.Addr().String())
	d.installHTTP2Session(fresh)
	check(stream, "after")
	next, _, err := fresh.openTunnel(ctx, "new.example:443", "Basic ZXhpdDp0ZXN0")
	if err != nil {
		t.Fatal(err)
	}
	defer next.Close()
	check(next, "new")
}
