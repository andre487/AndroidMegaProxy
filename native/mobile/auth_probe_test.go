package mobile

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
)

func TestAuthenticationProbeReveals407ThroughProductionHTTPSDialer(t *testing.T) {
	for _, h2 := range []bool{false, true} {
		t.Run(strconv.FormatBool(h2), func(t *testing.T) {
			var requests atomic.Int32
			server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				requests.Add(1)
				if r.Method != "CONNECT" || r.Host != "127.0.0.1:443" {
					t.Errorf("probe did not use configured target")
					w.WriteHeader(502)
					return
				}
				expected := "Basic " + base64.StdEncoding.EncodeToString([]byte("private-user:private-password"))
				if r.Header.Get("Proxy-Authorization") != expected {
					t.Error("probe did not use profile authentication")
				}
				w.WriteHeader(407)
			}))
			server.EnableHTTP2 = h2
			server.StartTLS()
			defer server.Close()
			host, port, _ := net.SplitHostPort(server.Listener.Addr().String())
			number, _ := strconv.Atoi(port)
			c := config{Type: "HTTPS", Host: "localhost", DialHost: host, Port: number, Username: "private-user", Password: "private-password", Profile: "CHROME_ANDROID", AllowInvalidProxyCertificate: true, DoHURL: "https://unused.invalid/dns-query", BypassLocalNetworks: true, AuthProbeHost: "127.0.0.1"}
			raw, _ := json.Marshal(c)
			logs := &diagnosticRecorder{}
			_, err := TestConnection(string(raw), &jumpTestProtector{}, logs)
			var rejected *httpConnectStatusError
			if !errors.As(err, &rejected) || rejected.status != 407 {
				t.Fatalf("wanted authentication rejection, got %v", err)
			}
			if requests.Load() != 1 {
				t.Fatalf("diagnostics continued after 407: %d requests", requests.Load())
			}
			if !strings.Contains(logs.text(), "event=proxy_auth_probe result=rejected reason=proxy_authentication status=407") {
				t.Fatal("missing structured authentication failure")
			}
			for _, private := range []string{"127.0.0.1", "localhost", "2001:db8::1", "[2001:db8::1]", "private-user", "private-password", "unused.invalid"} {
				if strings.Contains(logs.text(), private) {
					t.Fatal("private field leaked into probe logs")
				}
			}
		})
	}
}

func TestAuthenticationProbeDoesNotGuessFromTimeoutOrUnexpectedStatus(t *testing.T) {
	for _, tc := range []struct {
		err  error
		want string
	}{
		{context.DeadlineExceeded, "result=inconclusive reason=timeout status=0"},
		{&httpConnectStatusError{status: 404}, "result=inconclusive reason=connect_rejected status=404"},
		{errors.New("private-user private-password secret.example 192.0.2.1"), "result=inconclusive reason=other status=0"},
	} {
		logs := &diagnosticRecorder{}
		err := checkProxyAuthentication(context.Background(), "probe.example", func(ctx context.Context, target string) (net.Conn, error) {
			if _, ok := ctx.Deadline(); !ok {
				t.Fatal("probe must be bounded")
			}
			return nil, tc.err
		}, logs)
		if err != nil || !strings.Contains(logs.text(), tc.want) {
			t.Fatalf("probe guessed an authentication error: %v %s", err, logs.text())
		}
		for _, private := range []string{"private-user", "private-password", "secret.example", "192.0.2.1", "probe.example"} {
			if strings.Contains(logs.text(), private) {
				t.Fatal("peer-controlled data entered probe logs")
			}
		}
	}
	calls := 0
	if err := checkProxyAuthentication(context.Background(), "", func(context.Context, string) (net.Conn, error) { calls++; return nil, nil }, nil); err != nil || calls != 0 {
		t.Fatal("disabled probe opened a connection")
	}
}

func TestAuthenticationProbeAcceptedConnectRemainsUnconfirmedAndClosesStream(t *testing.T) {
	left, right := net.Pipe()
	defer right.Close()
	logs := &diagnosticRecorder{}
	err := checkProxyAuthentication(context.Background(), "probe.example", func(context.Context, string) (net.Conn, error) { return left, nil }, logs)
	if err != nil || !strings.Contains(logs.text(), "result=connect_accepted authentication=unconfirmed status=200") {
		t.Fatal("CONNECT 200 must not prove authentication")
	}
	if _, err := right.Write([]byte{1}); err == nil {
		t.Fatal("probe stream was not closed")
	}
}

func TestAuthenticationProbeHostnameValidation(t *testing.T) {
	for _, host := range []string{"", "knock.example", "127.0.0.1", "localhost", "2001:db8::1", "[2001:db8::1]"} {
		if !validAuthProbeHost(host) {
			t.Fatal("valid hostname rejected")
		}
	}
	for _, host := range []string{"https://knock.example", "name:443", "[2001:db8::1", "2001:db8::1]", "user@host", "host/path", "host\\path", "host?query", "host#fragment", "bad host", "bad\nname", "a..b", "-host", strings.Repeat("a", 64) + ".example", strings.Repeat("a.", 127) + "aa"} {
		if validAuthProbeHost(host) {
			t.Fatal("invalid hostname accepted")
		}
	}
}
