package mobile

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
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
