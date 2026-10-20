package mobile

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/refraction-networking/uquic/http3"
	utls "github.com/refraction-networking/utls"
	"golang.org/x/net/http2"
)

type speedLocalNetwork struct{ binds atomic.Int32 }

func (n *speedLocalNetwork) Bind(int) bool                { n.binds.Add(1); return true }
func (*speedLocalNetwork) Resolve(string) (string, error) { return "127.0.0.1", nil }

func TestSpeedProtocolsAndFallbackSafety(t *testing.T) {
	for _, p := range []string{"h3", "h2", "h1"} {
		chain := speedProtocols(p)
		if chain[0] != p || chain[len(chain)-1] != "h1" {
			t.Fatal(chain)
		}
	}
	for _, err := range []error{speedHTTPError(403), speedHTTPError(429), context.Canceled, x509.UnknownAuthorityError{}, errors.New("speed socket bind rejected"), io.ErrShortWrite} {
		if speedCanFallback(err) {
			t.Fatalf("unsafe fallback: %v", err)
		}
	}
	if !speedCanFallback(errSpeedALPN) || !speedCanFallback(context.DeadlineExceeded) {
		t.Fatal("missing protocol fallback")
	}
}

func TestSpeedMeasurementsUseBoundSocketsAndStreamResults(t *testing.T) {
	for _, protocol := range []string{"h1", "h2", "h3"} {
		t.Run(protocol, func(t *testing.T) {
			var uploads atomic.Int64
			handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.ProtoMajor != map[string]int{"h1": 1, "h2": 2, "h3": 3}[protocol] {
					t.Errorf("protocol %s", r.Proto)
				}
				switch r.URL.Path {
				case "/cdn-cgi/trace":
					fmt.Fprint(w, "ip=203.0.113.1\nloc=AM\ntls=TLSv1.3\n")
				case "/__down":
					n, _ := strconv.ParseInt(r.URL.Query().Get("bytes"), 10, 64)
					io.CopyN(w, speedZeroReader{}, n)
				case "/__up":
					n, _ := io.Copy(io.Discard, r.Body)
					uploads.Add(n)
				default:
					w.WriteHeader(404)
				}
			})
			server := httptest.NewUnstartedServer(handler)
			server.EnableHTTP2 = true
			server.StartTLS()
			defer server.Close()
			roots := x509.NewCertPool()
			roots.AddCert(server.Certificate())
			if protocol == "h3" {
				packet, err := net.ListenPacket("udp4", server.Listener.Addr().String())
				if err != nil {
					t.Fatal(err)
				}
				defer packet.Close()
				cert := server.TLS.Certificates[0]
				h3server := &http3.Server{Handler: handler, TLSConfig: &utls.Config{Certificates: []utls.Certificate{{Certificate: cert.Certificate, PrivateKey: cert.PrivateKey}}}}
				go func() { _ = h3server.Serve(packet) }()
				defer h3server.Close()
			}
			network := &speedLocalNetwork{}
			var updates []speedResult
			var logs []string
			reportUpdates := diagnosticFunc(func(raw string) {
				var r speedResult
				if err := json.Unmarshal([]byte(raw), &r); err != nil {
					t.Error(err)
				}
				updates = append(updates, r)
			})
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			err := runSpeedTest(ctx, protocol, 4096, reportUpdates, diagnosticFunc(func(s string) { logs = append(logs, s) }), server.URL, func(p string) (http.RoundTripper, func()) {
				transport, closeFn := speedTransport(network, p, nil)
				switch tr := transport.(type) {
				case *http.Transport:
					tr.TLSClientConfig.RootCAs = roots
				case *http2.Transport:
					tr.TLSClientConfig = &tls.Config{RootCAs: roots}
				case *http3.Transport:
					tr.TLSClientConfig = &utls.Config{RootCAs: roots}
				}
				return transport, closeFn
			})
			if err != nil {
				t.Fatal(err)
			}
			if len(updates) != 4 || updates[0].ExitIP != "203.0.113.1" || updates[0].LatencyMS != nil || updates[1].LatencyMS == nil || updates[1].DownloadMbps != nil || updates[2].DownloadMbps == nil || updates[2].UploadMbps != nil || updates[3].UploadMbps == nil {
				t.Fatalf("partial results: %+v", updates)
			}
			if uploads.Load() != 4096 || network.binds.Load() == 0 {
				t.Fatalf("upload=%d binds=%d", uploads.Load(), network.binds.Load())
			}
			for _, line := range logs {
				if strings.Contains(line, "203.0.113.1") {
					t.Fatal("private result leaked to log")
				}
			}
		})
	}
}

type speedFailTransport struct{ err error }

func (t speedFailTransport) RoundTrip(*http.Request) (*http.Response, error) { return nil, t.err }
func TestSpeedFallbackStopsOnHTTPAndCertificateErrors(t *testing.T) {
	for _, err := range []error{errSpeedALPN, speedHTTPError(403), x509.UnknownAuthorityError{}} {
		var attempts []string
		failure := runSpeedTest(context.Background(), "h3", 1, nil, nil, "https://unused.invalid", func(p string) (http.RoundTripper, func()) {
			attempts = append(attempts, p)
			return speedFailTransport{err}, func() {}
		})
		if failure == nil {
			t.Fatal("expected failure")
		}
		want := []string{"h3"}
		if errors.Is(err, errSpeedALPN) {
			want = []string{"h3", "h2", "h1"}
		}
		if !reflect.DeepEqual(attempts, want) {
			t.Fatalf("attempts=%v want=%v", attempts, want)
		}
	}
}

func TestSpeedCanceledBeforeRun(t *testing.T) {
	job := NewSpeedTest()
	job.Cancel()
	if err := job.Run(&speedLocalNetwork{}, "h3", 10, nil, nil); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}
