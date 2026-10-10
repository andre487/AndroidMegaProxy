package mobile

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestHTTPSPreferenceSelectsMASQUEAndKeepsAuthenticationTerminal(t *testing.T) {
	for _, wrongPassword := range []bool{false, true} {
		t.Run(map[bool]string{false: "success", true: "authentication"}[wrongPassword], func(t *testing.T) {
			c := masqueTestServer(t)
			c.Type, c.PreferHTTP3 = "HTTPS", true
			if wrongPassword {
				c.Password = "wrong"
			}
			logs := &diagnosticRecorder{}
			d, err := preferredHTTP3(context.Background(), c, &jumpTestProtector{}, logs, nil)
			if err != nil || d == nil {
				t.Fatalf("H3 selection failed: %v", err)
			}
			defer d.Close()
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			conn, err := d.connectTarget(ctx, "target.example:443")
			if wrongPassword {
				if conn != nil || errorClass(err) != "proxy_authentication" {
					t.Fatalf("authentication accepted: %v", err)
				}
			} else {
				if err != nil {
					t.Fatal(err)
				}
				defer conn.Close()
				if _, err := conn.Write([]byte("echo")); err != nil {
					t.Fatal(err)
				}
				reply := make([]byte, 4)
				if _, err := io.ReadFull(conn, reply); err != nil || string(reply) != "echo" {
					t.Fatalf("echo failed: %v", err)
				}
			}
			if strings.Contains(logs.text(), "result=fallback") {
				t.Fatal("selected H3 downgraded")
			}
		})
	}
}

func TestHTTPSPreferenceFallbackAndFatalErrors(t *testing.T) {
	base := masqueTestServer(t)
	base.Type, base.PreferHTTP3 = "HTTPS", true
	blackhole, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer blackhole.Close()
	for _, name := range []string{"unavailable", "certificate", "canceled", "protect", "fingerprint", "disabled"} {
		t.Run(name, func(t *testing.T) {
			c := base
			ctx := context.Background()
			protector := Protector(&jumpTestProtector{})
			wantFallback, wantFatal := false, false
			switch name {
			case "unavailable":
				c.Port = blackhole.LocalAddr().(*net.UDPAddr).Port
				wantFallback = true
			case "certificate":
				c.AllowInvalidProxyCertificate = false
				wantFatal = true
			case "canceled":
				canceled, cancel := context.WithCancel(ctx)
				cancel()
				ctx = canceled
				wantFatal = true
			case "protect":
				protector = rejectingProtector{}
				wantFatal = true
			case "fingerprint":
				c.Profile = "EDGE_ANDROID"
				wantFallback = true
			case "disabled":
				c.PreferHTTP3 = false
			}
			logs := &diagnosticRecorder{}
			d, err := preferredHTTP3(ctx, c, protector, logs, nil)
			if d != nil {
				d.Close()
				t.Fatal("unexpected H3 selection")
			}
			if (err != nil) != wantFatal {
				t.Fatalf("fatal=%v, error=%v", wantFatal, err)
			}
			if strings.Contains(logs.text(), "result=fallback") != wantFallback {
				t.Fatal(logs.text())
			}
			if strings.Contains(logs.text(), "dpi_hint=possible") {
				t.Fatal("optional probe triggered blocking detection")
			}
			if name == "canceled" && !errors.Is(err, context.Canceled) {
				t.Fatal(err)
			}
		})
	}
}

type rejectingProtector struct{}

func (rejectingProtector) Protect(int) bool { return false }

func TestHTTPSPreferenceRequiresDatagramsAndExtendedCONNECT(t *testing.T) {
	fixture := newMasqueFixtureWithDatagrams(t, nil, false, http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	c := fixture.config
	c.Type, c.PreferHTTP3 = "HTTPS", true
	logs := &diagnosticRecorder{}
	d, err := preferredHTTP3(context.Background(), c, &jumpTestProtector{}, logs, nil)
	if d != nil {
		d.Close()
		t.Fatal("incomplete settings selected")
	}
	if err != nil || !strings.Contains(logs.text(), "reason=unsupported_server_settings") {
		t.Fatalf("%v: %s", err, logs.text())
	}
}

func TestHTTPSPreferenceFallbackRetainsHTTP1AndHTTP2Traffic(t *testing.T) {
	for _, h2 := range []bool{false, true} {
		t.Run(map[bool]string{false: "http1", true: "http2"}[h2], func(t *testing.T) {
			server := jumpTestProxy(t, h2, "target.example:443", "user:password", "", false)
			host, port, _ := net.SplitHostPort(server.Listener.Addr().String())
			number, _ := strconv.Atoi(port)
			c := config{Type: "HTTPS", PreferHTTP3: true, Host: "localhost", DialHost: host, Port: number,
				Username: "user", Password: "password", Profile: "CHROME_ANDROID", AllowInvalidProxyCertificate: true}
			logs := &diagnosticRecorder{}
			d, err := preferredHTTP3(context.Background(), c, &jumpTestProtector{}, logs, nil)
			if d != nil || err != nil || !strings.Contains(logs.text(), "result=fallback") {
				t.Fatalf("fallback failed: %v", err)
			}
			https := &httpsConnectDialer{config: c, protector: &jumpTestProtector{}, reporter: logs}
			defer https.Close()
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			conn, err := https.connectTarget(ctx, "target.example:443")
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			if _, err := conn.Write([]byte("echo")); err != nil {
				t.Fatal(err)
			}
			reply := make([]byte, 4)
			if _, err := io.ReadFull(conn, reply); err != nil || string(reply) != "echo" {
				t.Fatalf("echo: %v", err)
			}
			want := "http_version=HTTP/1.1"
			if h2 {
				want = "http_version=HTTP/2"
			}
			if !strings.Contains(logs.text(), want) {
				t.Fatal(logs.text())
			}
		})
	}
}
