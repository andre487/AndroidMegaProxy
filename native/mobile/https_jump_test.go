package mobile

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type jumpTestProtector struct{ calls atomic.Int32 }

func (p *jumpTestProtector) Protect(int) bool { p.calls.Add(1); return true }

// Each server accepts exactly one CONNECT authority and credential pair. The
// jump maps a deliberately unresolvable destination hostname to a local server.
func jumpTestProxy(t *testing.T, h2 bool, authority, credentials, forward string, reject bool) *httptest.Server {
	t.Helper()
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodConnect || r.Host != authority {
			t.Errorf("unexpected request: %s %s", r.Method, r.Host)
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		if r.Header.Get("Proxy-Authorization") != "Basic "+base64.StdEncoding.EncodeToString([]byte(credentials)) {
			t.Error("wrong credentials at proxy hop")
			w.WriteHeader(http.StatusProxyAuthRequired)
			return
		}
		if reject {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		var upstream net.Conn
		if forward != "" {
			var err error
			upstream, err = net.DialTimeout("tcp", forward, 2*time.Second)
			if err != nil {
				t.Error(err)
				w.WriteHeader(http.StatusBadGateway)
				return
			}
			defer upstream.Close()
		}
		if r.ProtoMajor == 1 {
			conn, buffer, err := w.(http.Hijacker).Hijack()
			if err != nil {
				t.Error(err)
				return
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
			_, _ = buffer.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n")
			_ = buffer.Flush()
			if upstream == nil {
				_, _ = io.Copy(conn, buffer)
				return
			}
			done := make(chan struct{})
			go func() { _, _ = io.Copy(upstream, buffer); _ = upstream.Close(); close(done) }()
			_, _ = io.Copy(conn, upstream)
			_ = conn.Close()
			<-done
			return
		}
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		if upstream == nil {
			_, _ = io.Copy(jumpFlushWriter{w}, r.Body)
			return
		}
		done := make(chan struct{})
		go func() { _, _ = io.Copy(upstream, r.Body); _ = upstream.Close(); close(done) }()
		_, _ = io.Copy(jumpFlushWriter{w}, upstream)
		_ = r.Body.Close()
		<-done
	}))
	server.EnableHTTP2 = h2
	server.StartTLS()
	t.Cleanup(server.Close)
	return server
}

type jumpFlushWriter struct{ http.ResponseWriter }

func (w jumpFlushWriter) Write(p []byte) (int, error) {
	n, err := w.ResponseWriter.Write(p)
	w.ResponseWriter.(http.Flusher).Flush()
	return n, err
}

func TestHTTPSJumpTunnel(t *testing.T) {
	for _, jumpH2 := range []bool{false, true} {
		for _, exitH2 := range []bool{false, true} {
			t.Run(fmt.Sprintf("jump_h2=%t/exit_h2=%t", jumpH2, exitH2), func(t *testing.T) {
				exit := jumpTestProxy(t, exitH2, "site.invalid:443", "exit:exit-secret", "", false)
				jump := jumpTestProxy(t, jumpH2, "exit.invalid:443", "jump:jump-secret", exit.Listener.Addr().String(), false)
				host, port, _ := net.SplitHostPort(jump.Listener.Addr().String())
				portNumber, _ := strconv.Atoi(port)
				protector := &jumpTestProtector{}
				d := &httpsConnectDialer{protector: protector, config: config{
					Type: "HTTPS_JUMP", Host: "exit.invalid", Port: 443,
					// A direct dial to this address would fail. Only the jump can resolve exit.invalid.
					DialHost: "192.0.2.1", Username: "exit", Password: "exit-secret",
					JumpHost: "jump.invalid", JumpDialHost: host, JumpPort: portNumber,
					JumpUsername: "jump", JumpPassword: "jump-secret",
					AllowInvalidProxyCertificate: true, JumpAllowInvalidProxyCertificate: true,
					Profile: "CHROME_ANDROID", BypassLocalNetworks: true,
				}}
				defer d.Close()
				before := snapshotStats()
				for i := 0; i < 2; i++ {
					ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
					conn, err := d.connectTarget(ctx, "site.invalid:443")
					cancel()
					if err != nil {
						t.Fatal(err)
					}
					_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
					payload := "hello through two HTTPS proxies\n"
					if _, err := io.WriteString(conn, payload); err != nil {
						t.Fatal(err)
					}
					got, err := bufio.NewReader(conn).ReadString('\n')
					_ = conn.Close()
					if err != nil || got != payload {
						t.Fatalf("echo = %q, %v", got, err)
					}
				}
				after := snapshotStats()
				wantBytes := uint64(2 * len("hello through two HTTPS proxies\n"))
				if after.DownloadBytes-before.DownloadBytes != wantBytes || after.UploadBytes-before.UploadBytes != wantBytes {
					t.Fatal("intermediate TLS tunnel must not count application traffic twice")
				}
				if after.TotalOutcomes-before.TotalOutcomes != 2 {
					t.Fatal("only end-to-end connection outcomes should be counted")
				}
				if got := protector.calls.Load(); got < 1 || got > 2 {
					t.Fatalf("protected sockets = %d", got)
				}
				if d.jump == nil || d.jump.config.BypassLocalNetworks {
					t.Fatal("jump transport must not bypass CONNECT")
				}
			})
		}
	}
}

func TestHTTPSJumpRejectsFailedHop(t *testing.T) {
	for _, failure := range []string{"jump_certificate", "exit_certificate", "jump_connect", "exit_connect"} {
		t.Run(failure, func(t *testing.T) {
			exit := jumpTestProxy(t, false, "site.invalid:443", "user:secret", "", failure == "exit_connect")
			jump := jumpTestProxy(t, false, "exit.invalid:443", "user:secret", exit.Listener.Addr().String(), failure == "jump_connect")
			host, port, _ := net.SplitHostPort(jump.Listener.Addr().String())
			portNumber, _ := strconv.Atoi(port)
			protector := &jumpTestProtector{}
			d := &httpsConnectDialer{protector: protector, config: config{
				Type: "HTTPS_JUMP", Host: "exit.invalid", Port: 443,
				Username: "user", Password: "secret", SameJumpAuthentication: true,
				JumpHost: "jump.invalid", JumpDialHost: host, JumpPort: portNumber,
				AllowInvalidProxyCertificate:     failure != "exit_certificate",
				JumpAllowInvalidProxyCertificate: failure != "jump_certificate", Profile: "CHROME_ANDROID",
			}}
			defer d.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
			defer cancel()
			conn, err := d.connectTarget(ctx, "site.invalid:443")
			if conn != nil {
				_ = conn.Close()
				t.Fatal("failed hop yielded a connection")
			}
			if err == nil {
				t.Fatal("expected failed hop")
			}
			if strings.HasPrefix(failure, "jump") && !strings.Contains(err.Error(), "HTTPS jump") {
				t.Fatalf("missing hop context: %v", err)
			}
			if protector.calls.Load() != 1 {
				t.Fatal("failure must not trigger a direct connection")
			}
		})
	}
}

func TestHTTPSJumpConfigValidation(t *testing.T) {
	valid := config{Type: "HTTPS_JUMP", Host: "exit.invalid", Port: 443,
		Username: "u", Password: "p", JumpHost: "jump.invalid", JumpDialHost: "127.0.0.1", JumpPort: 443,
		SameJumpAuthentication: true, Profile: "CHROME_ANDROID", DoHURL: "https://dns.google/dns-query"}
	raw, _ := json.Marshal(valid)
	parsed, err := parseConfig(string(raw))
	if err != nil {
		t.Fatal(err)
	}
	if parsed.JumpUsername != "u" || parsed.JumpPassword != "p" {
		t.Fatal("shared credentials not applied")
	}
	for _, change := range []func(*config){
		func(c *config) { c.JumpHost = "" },
		func(c *config) { c.JumpPort = 0 },
		func(c *config) { c.JumpDialHost = "" },
		func(c *config) { c.SameJumpAuthentication = false },
		func(c *config) { c.Password = "" },
		func(c *config) { c.Profile = "invalid" },
	} {
		c := valid
		change(&c)
		raw, _ := json.Marshal(c)
		if _, err := parseConfig(string(raw)); err == nil {
			t.Fatal("invalid jump configuration accepted")
		}
	}
}
