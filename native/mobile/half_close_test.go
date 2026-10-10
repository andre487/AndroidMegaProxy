package mobile

import (
	"bufio"
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/tunnel/statistic"
)

func TestTCPHalfClosePreservesReplyThroughWrappers(t *testing.T) {
	for _, kind := range []string{"buffered", "ssh", "http2"} {
		t.Run(kind, func(t *testing.T) {
			var conn net.Conn
			if kind == "http2" {
				server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					w.WriteHeader(http.StatusOK)
					w.(http.Flusher).Flush()
					payload, err := io.ReadAll(r.Body)
					if err == nil {
						_, _ = w.Write(append([]byte("reply:"), payload...))
					}
				}))
				server.EnableHTTP2 = true
				server.StartTLS()
				defer server.Close()
				host, port, _ := net.SplitHostPort(server.Listener.Addr().String())
				number, _ := strconv.Atoi(port)
				d := &httpsConnectDialer{config: config{Host: host, DialHost: host, Port: number, Profile: "CHROME_ANDROID", Username: "exit", Password: "test", AllowInvalidProxyCertificate: true}, protector: &jumpTestProtector{}}
				defer d.Close()
				ctx, cancel := context.WithTimeout(context.Background(), time.Second)
				defer cancel()
				var err error
				conn, err = d.DialContext(ctx, &M.Metadata{DstIP: netip.MustParseAddr("192.0.2.1"), DstPort: 443})
				if err != nil {
					t.Fatal(err)
				}
			} else {
				listener, err := net.Listen("tcp", "127.0.0.1:0")
				if err != nil {
					t.Fatal(err)
				}
				defer listener.Close()
				go func() {
					peer, err := listener.Accept()
					if err != nil {
						return
					}
					defer peer.Close()
					_ = peer.SetDeadline(time.Now().Add(2 * time.Second))
					payload, err := io.ReadAll(peer)
					if err == nil {
						_, _ = peer.Write(append([]byte("reply:"), payload...))
					}
				}()
				conn, err = net.Dial("tcp", listener.Addr().String())
				if err != nil {
					t.Fatal(err)
				}
				if kind == "buffered" {
					conn = &bufferedConn{Conn: conn, reader: bufio.NewReader(conn)}
				} else {
					conn = &sshTrackedConn{Conn: conn, release: func() {}, bytes: &atomic.Uint64{}}
				}
				conn = &slotConn{Conn: &diagnosticConn{Conn: conn}, release: func() {}}
			}
			conn = statistic.NewTCPTracker(conn, &M.Metadata{}, statistic.DefaultManager)
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(time.Second))
			if _, err := conn.Write([]byte("request")); err != nil {
				t.Fatal(err)
			}
			if err := conn.(interface{ CloseWrite() error }).CloseWrite(); err != nil {
				t.Fatal(err)
			}
			payload, err := io.ReadAll(conn)
			if err != nil || string(payload) != "reply:request" {
				t.Fatalf("FIN/reply: %q %v", payload, err)
			}
		})
	}
}
