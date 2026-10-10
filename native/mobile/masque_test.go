package mobile

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"os"
	"strings"
	"testing"
	"time"

	quic "github.com/refraction-networking/uquic"
	"github.com/refraction-networking/uquic/http3"
	tls "github.com/refraction-networking/utls"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
)

type masqueFixture struct {
	config   config
	server   *http3.Server
	listener *quic.Listener
	serving  <-chan error
}

func masqueTestServer(t *testing.T) config { return newMasqueFixture(t, nil).config }

func newMasqueFixture(t *testing.T, wrap func(net.PacketConn) net.PacketConn, handler ...http.Handler) masqueFixture {
	return newMasqueFixtureWithDatagrams(t, wrap, true, handler...)
}

func newMasqueFixtureWithDatagrams(t *testing.T, wrap func(net.PacketConn) net.PacketConn, datagrams bool, handler ...http.Handler) masqueFixture {
	return newMasqueFixtureWithCID(t, wrap, datagrams, 4, handler...)
}

func newMasqueFixtureWithCID(t *testing.T, wrap func(net.PacketConn) net.PacketConn, datagrams bool, cidLength int, handler ...http.Handler) masqueFixture {
	t.Helper()
	certificateServer := httptest.NewTLSServer(nil)
	certificate := certificateServer.TLS.Certificates[0]
	certificateServer.Close()
	packet, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	if wrap != nil {
		packet = wrap(packet)
	}
	server := &http3.Server{EnableDatagrams: datagrams, TLSConfig: &tls.Config{Certificates: []tls.Certificate{{Certificate: certificate.Certificate, PrivateKey: certificate.PrivateKey}}}}
	server.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "CONNECT" || r.Header.Get("Proxy-Authorization") != "Basic "+base64.StdEncoding.EncodeToString([]byte("user:password")) {
			w.WriteHeader(407)
			return
		}
		if r.Proto == "connect-udp" {
			w.Header().Set("Capsule-Protocol", "?1")
		}
		w.WriteHeader(200)
		stream := w.(http3.HTTPStreamer).HTTPStream()
		defer stream.Close()
		if r.Proto == "connect-udp" {
			for {
				data, err := stream.ReceiveDatagram(r.Context())
				if err != nil {
					return
				}
				if err = stream.SendDatagram(data); err != nil {
					return
				}
			}
		}
		_, _ = io.Copy(stream, stream)
	})
	if len(handler) > 0 {
		server.Handler = handler[0]
	}
	transport := &quic.Transport{Conn: packet, ConnectionIDLength: cidLength}
	listener, err := transport.Listen(http3.ConfigureTLSConfig(server.TLSConfig), &quic.Config{EnableDatagrams: true})
	if err != nil {
		t.Fatal(err)
	}
	serving := make(chan error, 1)
	go func() { serving <- server.ServeListener(listener) }()
	t.Cleanup(func() { _ = server.Close(); _ = listener.Close(); _ = transport.Close(); _ = packet.Close() })
	return masqueFixture{server: server, listener: listener, serving: serving, config: config{Type: "MASQUE", Host: "localhost", DialHost: "127.0.0.1", Port: packet.LocalAddr().(*net.UDPAddr).Port, Username: "user", Password: "password", AllowInvalidProxyCertificate: true, Profile: "CHROME_ANDROID", DoHURL: "https://dns.google/dns-query"}}
}

func TestMASQUEStreamsAndDatagrams(t *testing.T) {
	base := masqueTestServer(t)
	for _, profile := range []string{"CHROME_ANDROID", "FIREFOX_ANDROID", "RANDOMIZED", "CUSTOM"} {
		t.Run(profile, func(t *testing.T) {
			c := base
			c.Profile = profile
			c.CustomJA3 = "771,4865-4866-4867,0-10-13-16-43-51-57,29-23,0"
			protector := &jumpTestProtector{}
			stats := &connectionStats{}
			d := &masqueDialer{config: c, protector: protector, stats: stats}
			defer d.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			first, err := d.connectTarget(ctx, "target.example:443")
			if err != nil {
				t.Fatal(err)
			}
			defer first.Close()
			second, err := d.connectTarget(ctx, "other.example:443")
			if err != nil {
				t.Fatal(err)
			}
			defer second.Close()
			for _, conn := range []net.Conn{first, second} {
				_ = conn.SetDeadline(time.Now().Add(time.Second))
				payload := bytes.Repeat([]byte("echo"), 4096)
				if _, err := conn.Write(payload); err != nil {
					t.Fatal(err)
				}
				got := make([]byte, len(payload))
				if _, err := io.ReadFull(conn, got); err != nil || !bytes.Equal(got, payload) {
					t.Fatalf("echo: %v", err)
				}
			}
			udp, err := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr("192.0.2.1"), DstPort: 443})
			if err != nil {
				t.Fatal(err)
			}
			defer udp.Close()
			_ = udp.SetDeadline(time.Now().Add(time.Second))
			target := &net.UDPAddr{IP: net.ParseIP("192.0.2.1"), Port: 443}
			payload := []byte("datagram")
			if _, err := udp.WriteTo(payload, target); err != nil {
				t.Fatal(err)
			}
			buffer := make([]byte, 100)
			n, addr, err := udp.ReadFrom(buffer)
			if err != nil || addr.String() != target.String() || !bytes.Equal(buffer[:n], payload) {
				t.Fatalf("UDP echo: n=%d addr=%v err=%v", n, addr, err)
			}
			if protector.calls.Load() != 1 {
				t.Fatalf("expected one protected shared QUIC socket, got %d", protector.calls.Load())
			}
			if stats.uploadBytes.Load() != 32768+uint64(len(payload)) || stats.downloadBytes.Load() != stats.uploadBytes.Load() {
				t.Fatal("missing traffic accounting")
			}
			metrics := stats.snapshot(time.Now())
			if metrics.QUICRTTMillis == nil || *metrics.QUICRTTMillis <= 0 || metrics.QUICPacketsLost == nil || len(stats.quicSessions) != 1 {
				t.Fatalf("missing / duplicated shared QUIC metrics: %+v", metrics)
			}
			if metrics.TCPRTTMillis != nil || metrics.TCPRetransmits != nil {
				t.Fatal("MASQUE reported TCP kernel metrics")
			}
			_ = first.SetReadDeadline(time.Now())
			if _, err := first.Read(buffer); !errors.Is(err, os.ErrDeadlineExceeded) {
				t.Fatalf("TCP deadline: %v", err)
			}
			_ = udp.SetReadDeadline(time.Now())
			if _, _, err := udp.ReadFrom(buffer); !errors.Is(err, os.ErrDeadlineExceeded) {
				t.Fatalf("UDP deadline: %v", err)
			}
			_ = udp.SetReadDeadline(time.Time{})
			_ = d.Close()
			metrics = stats.snapshot(time.Now())
			if metrics.QUICRTTMillis != nil || metrics.QUICPacketsLost == nil {
				t.Fatal("closed QUIC session kept RTT or lost its final counter")
			}
			if _, err := d.connectTarget(ctx, "target.example:443"); !errors.Is(err, net.ErrClosed) {
				t.Fatalf("closed dialer: %v", err)
			}
		})
	}
}

func TestMASQUETrustAuthenticationAndCancellation(t *testing.T) {
	base := masqueTestServer(t)
	for _, failure := range []string{"password", "certificate"} {
		t.Run(failure, func(t *testing.T) {
			c := base
			if failure == "password" {
				c.Password = "wrong"
			} else {
				c.AllowInvalidProxyCertificate = false
			}
			d := &masqueDialer{config: c, protector: &jumpTestProtector{}}
			defer d.Close()
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			conn, err := d.connectTarget(ctx, "target.example:443")
			if conn != nil || err == nil {
				t.Fatal("invalid credentials/trust accepted")
			}
			want := "407"
			if failure == "certificate" {
				want = "certificate"
			}
			if !strings.Contains(err.Error(), want) {
				t.Fatal(err)
			}
		})
	}
	blackhole, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer blackhole.Close()
	base.Port = blackhole.LocalAddr().(*net.UDPAddr).Port
	recorder := &diagnosticRecorder{}
	timed := &masqueDialer{config: base, protector: &jumpTestProtector{}, reporter: recorder}
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	_, err = timed.getSession(ctx)
	cancel()
	_ = timed.Close()
	if err == nil || !strings.Contains(recorder.text(), "stage=tls_handshake result=failed reason=timeout dpi_hint=possible_tls_interference") {
		t.Fatalf("QUIC timeout was not exposed to recovery: %s", recorder.text())
	}
	d := &masqueDialer{config: base, protector: &jumpTestProtector{}}
	done := make(chan error, 1)
	go func() { _, err := d.getSession(context.Background()); done <- err }()
	time.Sleep(20 * time.Millisecond)
	_ = d.Close()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancelled handshake succeeded")
		}
	case <-time.After(time.Second):
		t.Fatal("Stop did not cancel QUIC handshake")
	}
}

func TestMASQUERoutingPolicy(t *testing.T) {
	c := masqueTestServer(t)
	c.BypassLocalNetworks = true
	c.DoHFallbackURLs = []string{"https://fallback.example/dns-query"}
	protector := &jumpTestProtector{}
	d := &masqueDialer{config: c, protector: protector}
	defer d.Close()
	if _, err := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr("2001:db8::1"), DstPort: 443}); err == nil {
		t.Fatal("IPv4-only policy accepted IPv6 UDP")
	}
	if _, err := d.connectTarget(context.Background(), "[2001:db8::1]:443"); err == nil {
		t.Fatal("IPv4-only policy accepted IPv6 TCP")
	}
	dns, err := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr("192.0.2.53"), DstPort: 53})
	if err != nil {
		t.Fatal(err)
	}
	defer dns.Close()
	doh, ok := dns.(*dohPacketConn)
	if !ok || len(doh.endpoints) != 2 || doh.endpoints[0] != c.DoHURL || doh.endpoints[1] != c.DoHFallbackURLs[0] {
		t.Fatal("DNS did not retain selected DoH and fallback policy")
	}
	origin, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer origin.Close()
	go func() {
		b := make([]byte, 32)
		n, peer, err := origin.ReadFrom(b)
		if err == nil {
			_, _ = origin.WriteTo(b[:n], peer)
		}
	}()
	target := origin.LocalAddr().(*net.UDPAddr)
	udp, err := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr("127.0.0.1"), DstPort: uint16(target.Port)})
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	_ = udp.SetDeadline(time.Now().Add(time.Second))
	payload := []byte("protected local bypass")
	if _, err := udp.WriteTo(payload, target); err != nil {
		t.Fatal(err)
	}
	b := make([]byte, 32)
	n, _, err := udp.ReadFrom(b)
	if err != nil || !bytes.Equal(b[:n], payload) {
		t.Fatalf("local UDP bypass: %v", err)
	}
	if protector.calls.Load() != 1 || d.session != nil {
		t.Fatal("local bypass must use a protected socket without a QUIC session")
	}
}

// The advertised send budget must fit a real packet after PMTU discovery and
// after the peer replaces the client's initial destination CID.
func TestMASQUEDatagramBudgetAfterMTUDiscovery(t *testing.T) {
	for _, cidLength := range []int{4, 20} {
		t.Run(fmt.Sprint(cidLength), func(t *testing.T) {
			fixture := newMasqueFixtureWithCID(t, nil, true, cidLength)
			d := &masqueDialer{config: fixture.config, protector: &jumpTestProtector{}}
			defer d.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			stream, err := d.openTunnel(ctx, "target.example:443", true)
			if err != nil {
				t.Fatal(err)
			}
			defer stream.Close()
			// Warm this direct path independently of the larger nested-path
			// browser budget (20-byte peer CIDs can still carry direct UDP).
			minimum := int64(1452 - 20 - (1 + max(8, cidLength) + 4 + 16 + 3))
			for stream.session.conn.DatagramPayloadLimit() < minimum {
				request, _ := http.NewRequestWithContext(ctx, http.MethodOptions, "https://localhost/", nil)
				response, err := stream.session.client.RoundTrip(request)
				if err != nil {
					t.Fatal(err)
				}
				response.Body.Close()
				select {
				case <-ctx.Done():
					t.Fatal(ctx.Err())
				case <-time.After(100 * time.Millisecond):
				}
			}
			// First request has a one-byte quarter-stream ID and context ID.
			limit := int(stream.session.conn.DatagramPayloadLimit())
			if limit < 1352 || limit > 1452-(1+cidLength+4+16+3) {
				t.Fatalf("unsafe packet budget %d for CID %d", limit, cidLength)
			}
			payload := bytes.Repeat([]byte{0x42}, limit-2)
			framed := append([]byte{0}, payload...)
			if err := stream.SendDatagram(framed); err != nil {
				t.Fatal(err)
			}
			got, err := stream.ReceiveDatagram(ctx)
			if err != nil || !bytes.Equal(got, framed) {
				t.Fatalf("maximum admitted DATAGRAM lost: %v", err)
			}
			var tooLarge *quic.DatagramTooLargeError
			if err := stream.session.conn.SendDatagram(make([]byte, limit+1)); !errors.As(err, &tooLarge) {
				t.Fatalf("oversized DATAGRAM admitted: %v", err)
			}
		})
	}
}
