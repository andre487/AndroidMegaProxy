package mobile

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"

	"golang.org/x/crypto/ssh"
)

type diagnosticRecorder struct {
	mu    sync.Mutex
	lines []string
}

func (r *diagnosticRecorder) Report(message string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lines = append(r.lines, message)
}
func (r *diagnosticRecorder) text() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return strings.Join(r.lines, "\n")
}

func TestNegotiationDetailsAreReadableAndAllowlisted(t *testing.T) {
	for _, tc := range []struct {
		version uint16
		name    string
	}{{tls.VersionTLS12, "TLS1.2"}, {tls.VersionTLS13, "TLS1.3"}} {
		got := tlsNegotiationDetails(tc.version, tls.TLS_AES_128_GCM_SHA256, "h2", true)
		for _, want := range []string{"tls_version=" + tc.name, "cipher=TLS_AES_128_GCM_SHA256", "alpn=h2", "session_resumed=true"} {
			if !strings.Contains(got, want) {
				t.Fatalf("missing %q: %s", want, got)
			}
		}
	}
	for _, private := range []string{"private.example", "192.0.2.123", "2001:db8::1", "private-user", "private-password", "h2\npassword=secret", "h2 private.example"} {
		got := tlsNegotiationDetails(tls.VersionTLS13, 0xffff, private, false)
		if strings.Contains(got, private) || !strings.Contains(got, "alpn=other") {
			t.Fatal("untrusted ALPN entered log")
		}
		if sshLogAlgorithm(private) != "unknown" {
			t.Fatal("untrusted SSH algorithm entered log")
		}
	}
	if normalizedALPN("") != "none" || normalizedALPN("http/1.1") != "http/1.1" {
		t.Fatal("known ALPN lost")
	}
	a := ssh.NegotiatedAlgorithms{
		KeyExchange: ssh.KeyExchangeCurve25519, HostKey: ssh.KeyAlgoED25519,
		Write: ssh.DirectionAlgorithms{Cipher: ssh.CipherChaCha20Poly1305},
		Read:  ssh.DirectionAlgorithms{Cipher: ssh.CipherAES128CTR, MAC: ssh.HMACSHA256ETM},
	}
	got := sshNegotiationDetails(a)
	for _, want := range []string{"kex=curve25519-sha256", "host_key_algorithm=ssh-ed25519", "c2s_cipher=chacha20-poly1305_openssh", "c2s_mac=aead", "s2c_cipher=aes128-ctr", "s2c_mac=hmac-sha2-256-etm_openssh"} {
		if !strings.Contains(got, want) {
			t.Fatalf("missing %s: %s", want, got)
		}
	}
	if strings.ContainsAny(got, "@.\r\n") {
		t.Fatal("vendor domains must not enter logs")
	}
}

type unsupportedHTTP2Client struct{ rejectedHTTP2Client }

func (c *unsupportedHTTP2Client) RoundTrip(r *http.Request) (*http.Response, error) {
	_ = r.Body.Close()
	return &http.Response{StatusCode: http.StatusMethodNotAllowed, Body: io.NopCloser(strings.NewReader(""))}, nil
}

func TestHTTPVersionLogAfterFallback(t *testing.T) {
	server := jumpTestProxy(t, false, "site.invalid:443", "user:secret", "", false)
	host, port, _ := net.SplitHostPort(server.Listener.Addr().String())
	number, _ := strconv.Atoi(port)
	logs := &diagnosticRecorder{}
	d := &httpsConnectDialer{protector: &jumpTestProtector{}, reporter: logs,
		config:    config{Host: host, DialHost: host, Port: number, Username: "user", Password: "secret", Profile: "CHROME_ANDROID", AllowInvalidProxyCertificate: true},
		h2Session: &http2ConnectSession{client: &unsupportedHTTP2Client{}},
	}
	defer d.Close()
	conn, err := d.connectTarget(context.Background(), "site.invalid:443")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	output := logs.text()
	if !strings.Contains(output, "action=fallback_http1") || !strings.Contains(output, "http_version=HTTP/1.1 stage=tunnel result=established") {
		t.Fatalf("missing fallback result: %s", output)
	}
	if strings.Contains(output, "http_version=HTTP/2 stage=tunnel result=established") {
		t.Fatal("reported a failed HTTP/2 attempt as established")
	}
}

func TestTLSHandshakeReportsActualVersionWithoutServerStatusText(t *testing.T) {
	for _, version := range []uint16{tls.VersionTLS12, tls.VersionTLS13} {
		t.Run(tls.VersionName(version), func(t *testing.T) {
			server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				conn, buffer, err := w.(http.Hijacker).Hijack()
				if err != nil {
					t.Error(err)
					return
				}
				defer conn.Close()
				buffer.WriteString("HTTP/1.1 403 private.example private-login private-password 192.0.2.123\r\nContent-Length: 0\r\n\r\n")
				buffer.Flush()
			}))
			server.TLS = &tls.Config{MinVersion: version, MaxVersion: version}
			server.StartTLS()
			defer server.Close()
			host, port, _ := net.SplitHostPort(server.Listener.Addr().String())
			number, _ := strconv.Atoi(port)
			logs := &diagnosticRecorder{}
			d := &httpsConnectDialer{protector: &jumpTestProtector{}, reporter: logs, config: config{Host: host, DialHost: host, Port: number, Username: "private-login", Password: "private-password", Profile: "CHROME_ANDROID", AllowInvalidProxyCertificate: true}}
			defer d.Close()
			_, err := d.connectTarget(context.Background(), "private.example:443")
			if err == nil || err.Error() != "proxy CONNECT returned status 403" {
				t.Fatalf("unsafe or missing rejection: %v", err)
			}
			output := logs.text()
			if !strings.Contains(output, "tls_version="+strings.ReplaceAll(tls.VersionName(version), " ", "")) {
				t.Fatal("did not report actual negotiated version")
			}
			for _, secret := range []string{"private.example", "private-login", "private-password", "192.0.2.123", host} {
				if strings.Contains(output, secret) {
					t.Fatal("private handshake information entered native log")
				}
			}
		})
	}
}
