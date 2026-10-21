package mobile

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync/atomic"
	"testing"
	"time"
)

// Compare the existing parallel handshakes against a single-opening gate using
// the production dialer and a local HTTP/2 proxy. The first handshake is slow.
func BenchmarkHTTP2ColdStart(b *testing.B) {
	for _, sharedOpening := range []bool{false, true} {
		b.Run(fmt.Sprintf("single_opening=%t", sharedOpening), func(b *testing.B) {
			var firstTotal time.Duration
			var handshakeTotal int32
			for range b.N {
				b.StopTimer()
				var handshakes atomic.Int32
				firstStarted := make(chan struct{})
				server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					w.WriteHeader(200)
					_ = http.NewResponseController(w).Flush()
					<-r.Context().Done()
				}))
				server.EnableHTTP2 = true
				server.TLS = &tls.Config{GetConfigForClient: func(*tls.ClientHelloInfo) (*tls.Config, error) {
					if handshakes.Add(1) == 1 {
						close(firstStarted)
						time.Sleep(80 * time.Millisecond)
					} else {
						time.Sleep(5 * time.Millisecond)
					}
					return nil, nil
				}}
				server.StartTLS()
				host, port, _ := net.SplitHostPort(server.Listener.Addr().String())
				number, _ := strconv.Atoi(port)
				d := &httpsConnectDialer{config: config{Type: "HTTPS", Host: host, DialHost: host, Port: number, Username: "test", Password: "test", AllowInvalidProxyCertificate: true, Profile: "CHROME_ANDROID"}, protector: &jumpTestProtector{}}
				ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				type result struct {
					conn net.Conn
					err  error
				}
				out := make(chan result, 8)
				ready := make(chan struct{})
				b.StartTimer()
				started := time.Now()
				go func() { conn, err := d.connectTarget(ctx, "target.test:443"); close(ready); out <- result{conn, err} }()
				<-firstStarted
				for range 7 {
					go func() {
						if sharedOpening {
							<-ready
						}
						conn, err := d.connectTarget(ctx, "target.test:443")
						out <- result{conn, err}
					}()
				}
				for index := range 8 {
					r := <-out
					if index == 0 {
						firstTotal += time.Since(started)
					}
					if r.err != nil {
						b.Fatal(r.err)
					}
					r.conn.Close()
				}
				b.StopTimer()
				handshakeTotal += handshakes.Load()
				cancel()
				d.Close()
				server.Close()
			}
			b.ReportMetric(float64(firstTotal.Microseconds())/float64(b.N)/1000, "first-CONNECT-ms")
			b.ReportMetric(float64(handshakeTotal)/float64(b.N), "handshakes/op")
		})
	}
}

func BenchmarkDNSFallbackLatency(b *testing.B) {
	for _, parallel := range []bool{false, true} {
		for _, slow := range []bool{false, true} {
			b.Run(fmt.Sprintf("parallel=%t/slow_primary=%t", parallel, slow), func(b *testing.B) {
				attempt := func(ctx context.Context, index int) (int, error) {
					delay := 5 * time.Millisecond
					if slow && index == 0 {
						delay = 350 * time.Millisecond
					}
					timer := time.NewTimer(delay)
					defer timer.Stop()
					select {
					case <-ctx.Done():
						return 0, ctx.Err()
					case <-timer.C:
					}
					if slow && index == 0 {
						return 0, context.DeadlineExceeded
					}
					return index, nil
				}
				for range b.N {
					if parallel {
						if _, err := raceFirst(context.Background(), 2, attempt, nil); err != nil {
							b.Fatal(err)
						}
					} else {
						if _, err := attempt(context.Background(), 0); err != nil {
							if _, err := attempt(context.Background(), 1); err != nil {
								b.Fatal(err)
							}
						}
					}
				}
			})
		}
	}
}
