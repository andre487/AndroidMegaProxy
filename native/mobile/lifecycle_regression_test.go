package mobile

import (
	"context"
	"errors"
	S "github.com/xjasonlyu/tun2socks/v2/transport/socks5"
	"golang.org/x/crypto/ssh"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"testing"
	"time"
)

func lifecycleHTTPS(t *testing.T, handler http.HandlerFunc) *httpsConnectDialer {
	t.Helper()
	server := httptest.NewUnstartedServer(handler)
	server.StartTLS()
	t.Cleanup(server.Close)
	host, port, _ := net.SplitHostPort(server.Listener.Addr().String())
	number, _ := strconv.Atoi(port)
	d := &httpsConnectDialer{config: config{Host: host, DialHost: host, Port: number, Profile: "CHROME_ANDROID", Username: "user", Password: "test", AllowInvalidProxyCertificate: true}, protector: &jumpTestProtector{}}
	t.Cleanup(func() { _ = d.Close() })
	return d
}

func TestHTTPSCloseMustCloseHTTP1Tunnel(t *testing.T) {
	d := lifecycleHTTPS(t, func(w http.ResponseWriter, r *http.Request) {
		c, b, err := w.(http.Hijacker).Hijack()
		if err != nil {
			return
		}
		defer c.Close()
		_, _ = b.WriteString("HTTP/1.1 200 OK\r\n\r\n")
		_ = b.Flush()
		_, _ = io.Copy(c, c)
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	c, err := d.connectTarget(ctx, "example.test:443")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	cancel() // A completed tunnel must outlive the setup context.
	_ = c.SetDeadline(time.Now().Add(time.Second))
	if _, err := c.Write([]byte("alive")); err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadFull(c, make([]byte, 5)); err != nil {
		t.Fatal(err)
	}
	_ = d.Close()
	_ = c.SetDeadline(time.Now().Add(time.Second))
	_, err = c.Write([]byte("still-open"))
	if err == nil {
		b := make([]byte, 10)
		_, err = io.ReadFull(c, b)
	}
	if err == nil {
		t.Fatal("HTTP/1 tunnel still transfers bytes after dialer.Close")
	}
}

func TestHTTPSCancelMustWakeCONNECT(t *testing.T) {
	for _, stopping := range []string{"cancel", "close"} {
		t.Run(stopping, func(t *testing.T) {
			entered, release := make(chan struct{}), make(chan struct{})
			defer close(release)
			d := lifecycleHTTPS(t, func(w http.ResponseWriter, r *http.Request) {
				c, _, err := w.(http.Hijacker).Hijack()
				if err != nil {
					return
				}
				defer c.Close()
				close(entered)
				<-release
			})
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			go func() {
				c, e := d.connectTarget(ctx, "example.test:443")
				if c != nil {
					c.Close()
				}
				done <- e
			}()
			select {
			case <-entered:
			case <-time.After(3 * time.Second):
				t.Fatal("CONNECT not sent")
			}
			if stopping == "cancel" {
				cancel()
			} else {
				_ = d.Close()
			}
			select {
			case <-done:
			case <-time.After(300 * time.Millisecond):
				t.Error("cancel/Close did not wake pending HTTP/1 CONNECT")
			}
		})
	}
}

type allocationPacketSocket struct {
	net.Conn
	packet []byte
}

func (c *allocationPacketSocket) Read(b []byte) (int, error) { return copy(b, c.packet), nil }
func BenchmarkSOCKS5ReadAllocation(b *testing.B) {
	target := S.ParseAddrString("192.0.2.1:443")
	packet, err := S.EncodeUDPPacket(target, make([]byte, 1200))
	if err != nil {
		b.Fatal(err)
	}
	p := &socks5PacketConn{Conn: &allocationPacketSocket{packet: packet}, target: target, peer: &net.UDPAddr{IP: net.IPv4(192, 0, 2, 1), Port: 443}}
	buffer := make([]byte, 1400)
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _, err := p.ReadFrom(buffer)
		if err != nil && !errors.Is(err, io.EOF) {
			b.Fatal(err)
		}
	}
}

func TestSOCKS5ReadAllocationBudget(t *testing.T) {
	result := testing.Benchmark(BenchmarkSOCKS5ReadAllocation)
	if bytes := result.AllocedBytesPerOp(); bytes > 2048 {
		t.Fatalf("UDP read allocates %d bytes per datagram", bytes)
	}
}

type silentKeepaliveConn struct {
	ssh.Conn
	entered  chan struct{}
	closed   chan struct{}
	finished chan struct{}
	once     sync.Once
}

func (c *silentKeepaliveConn) SendRequest(string, bool, []byte) (bool, []byte, error) {
	close(c.entered)
	<-c.closed
	close(c.finished)
	return false, nil, io.EOF
}
func (c *silentKeepaliveConn) Close() error { c.once.Do(func() { close(c.closed) }); return nil }

func TestSSHKeepaliveTimeoutClosesSilentSession(t *testing.T) {
	raw := &silentKeepaliveConn{entered: make(chan struct{}), closed: make(chan struct{}), finished: make(chan struct{})}
	d := &sshDialer{client: &ssh.Client{Conn: raw}, config: config{SSHKeepaliveSeconds: 1}}
	defer d.Close()
	d.startKeepalive()
	select {
	case <-raw.entered:
	case <-time.After(3 * time.Second):
		t.Fatal("keepalive not sent")
	}
	select {
	case <-raw.closed:
	case <-time.After(12 * time.Second):
		t.Fatal("silent keepalive did not invalidate session")
	}
	select {
	case <-raw.finished:
	case <-time.After(time.Second):
		t.Fatal("keepalive worker leaked")
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.client != nil {
		t.Fatal("dead SSH session retained")
	}
}

func TestHTTPSCloseWakesConnectionAdmission(t *testing.T) {
	d := &httpsConnectDialer{connections: make(chan struct{}, 1)}
	d.connections <- struct{}{}
	done := make(chan error, 1)
	go func() { _, err := d.DialContext(context.Background(), nil); done <- err }()
	defer d.Close()
	deadline := time.Now().Add(time.Second)
	for {
		d.cacheMu.Lock()
		waiting := d.lifetime != nil
		d.cacheMu.Unlock()
		if waiting {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("dial did not reach admission")
		}
		time.Sleep(time.Millisecond)
	}
	_ = d.Close()
	select {
	case err := <-done:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("unexpected admission error: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Close did not wake connection admission")
	}
}
