package mobile

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestPlainDNSValidationAndLocalConsent(t *testing.T) {
	for _, mode := range []string{"tcp", "udp"} {
		for _, server := range []string{"192.0.2.1", "192.0.2.1:5353", "[2001:db8::1]", "[::1]:5353"} {
			endpoint := mode + "://" + server
			if _, _, err := plainDNSTarget(endpoint); err != nil {
				t.Fatal(endpoint, err)
			}
			raw := `{"type":"SOCKS5","host":"proxy.example","dialHost":"127.0.0.1","port":1080,"dohUrl":"` + endpoint + `"`
			if _, err := parseConfig(raw + `}`); err == nil {
				t.Fatal("missing consent accepted")
			}
			if _, err := parseConfig(raw + `,"allowInsecureDns":true}`); err != nil {
				t.Fatal(err)
			}
			if _, err := parseConfig(raw + `,"allowInsecureDns":true,"dohFallbackUrls":["https://dns.example"]}`); err == nil {
				t.Fatal("plain DNS fallback accepted")
			}
		}
		for _, server := range []string{"dns.example", "192.0.2.1:0", "192.0.2.1:65536", "2001:db8::1", "192.0.2.1/path", "192.0.2.1?x=1", "192.0.2.1#"} {
			if _, _, err := plainDNSTarget(mode + "://" + server); err == nil {
				t.Fatal("invalid endpoint accepted", server)
			}
		}
	}
	c := config{DoHURL: "tcp://192.168.1.1", BypassLocalNetworks: true}
	if c.bypassTarget("192.168.1.1:53") || !c.bypassTarget("192.168.1.2:53") {
		t.Fatal("explicit resolver bypassed the proxy or LAN policy lost")
	}
}

func TestPlainDNSRoundTripsAndUDPFailureDoesNotFallBack(t *testing.T) {
	query, _, _ := buildAQuery("private.test")
	want := append([]byte(nil), query...)
	want[2] |= 0x80
	tcp, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer tcp.Close()
	go func() {
		conn, err := tcp.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		var size [2]byte
		if _, err = io.ReadFull(conn, size[:]); err != nil {
			return
		}
		body := make([]byte, binary.BigEndian.Uint16(size[:]))
		if _, err = io.ReadFull(conn, body); err != nil {
			return
		}
		body[2] |= 0x80
		_, _ = conn.Write(append(size[:], body...))
	}()
	udp, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	go func() {
		buffer := make([]byte, 512)
		n, peer, err := udp.ReadFrom(buffer)
		if err == nil {
			buffer[2] |= 0x80
			_, _ = udp.WriteTo(buffer[:n], peer)
		}
	}()
	for _, mode := range []string{"tcp", "udp"} {
		target := tcp.Addr().String()
		if mode == "udp" {
			target = udp.LocalAddr().String()
		}
		endpoint := mode + "://" + target
		c := newDoHPacketConn(config{AllowInsecureDNS: true, DoHURL: endpoint}, nil, func(ctx context.Context, target string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, "tcp", target)
		}, endpoint)
		c.connectUDP = func(context.Context, string) (net.PacketConn, error) { return net.ListenPacket("udp4", "127.0.0.1:0") }
		reply, err := c.queryEndpoint(endpoint, query)
		c.Close()
		if err != nil || !bytes.Equal(reply, want) {
			t.Fatalf("%s: %x %v", mode, reply, err)
		}
	}
	c := newDoHPacketConn(config{AllowInsecureDNS: true, DoHURL: "udp://127.0.0.1:53"}, nil, nil, "udp://127.0.0.1:53")
	defer c.Close()
	calls := 0
	sentinel := errors.New("tunnel unavailable")
	c.connectUDP = func(context.Context, string) (net.PacketConn, error) { calls++; return nil, sentinel }
	if _, err := c.queryEndpoint(c.config.DoHURL, query); !errors.Is(err, sentinel) || calls != 1 {
		t.Fatalf("UDP failure was retried: %v calls=%d", err, calls)
	}
}

func TestDoHSelfSignedCertificateRequiresExplicitScopedException(t *testing.T) {
	query, _, _ := buildAQuery("private.test")
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		body[2] |= 0x80
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(body)
	}))
	defer server.Close()
	for _, allow := range []bool{false, true, false} {
		c := newDoHPacketConn(config{AllowInvalidDoHCertificate: allow}, nil, func(ctx context.Context, target string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, "tcp", target)
		}, server.URL)
		_, err := c.queryEndpoint(server.URL, query)
		c.Close()
		if (err == nil) != allow {
			t.Fatalf("allow=%v error=%v", allow, err)
		}
	}
	c := config{AllowInvalidDoHCertificate: true}
	if c.AllowInvalidProxyCertificate || c.JumpAllowInvalidProxyCertificate {
		t.Fatal("DoH exception changed proxy trust")
	}
}

func TestPlainDNSCancellationAndIPv6Policy(t *testing.T) {
	query, _, _ := buildAQuery("private.test")
	c := newDoHPacketConn(config{AllowInsecureDNS: true}, nil, func(context.Context, string) (net.Conn, error) {
		a, b := net.Pipe()
		go func() { defer b.Close(); _, _ = io.Copy(io.Discard, b) }()
		return a, nil
	}, "tcp://127.0.0.1:53")
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if _, err := c.queryPlainDNS(ctx, "tcp://127.0.0.1:53", query); err == nil {
		t.Fatal("cancellation ignored")
	}
	if _, err := c.queryPlainDNS(context.Background(), "tcp://[::1]:53", query); err == nil {
		t.Fatal("IPv6 policy ignored")
	}
}
