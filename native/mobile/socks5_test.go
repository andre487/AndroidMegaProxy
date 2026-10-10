package mobile

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	S "github.com/xjasonlyu/tun2socks/v2/transport/socks5"
	"net/netip"
)

// Disposable SOCKS peer validates the wire protocol; no personal/public proxy.
func socks5Fixture(t *testing.T, username, password string, server func(net.Conn, S.Command, S.Addr)) config {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })
	go func() {
		for {
			c, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				_ = c.SetDeadline(time.Now().Add(5 * time.Second))
				greeting := make([]byte, 3)
				if _, err := io.ReadFull(c, greeting); err != nil {
					return
				}
				method := byte(0)
				if username != "" {
					method = 2
				}
				if !bytes.Equal(greeting, []byte{5, 1, method}) {
					t.Error("wrong method offered")
					return
				}
				_, _ = c.Write([]byte{5, method})
				if method == 2 {
					var header [2]byte
					if _, err := io.ReadFull(c, header[:]); err != nil {
						return
					}
					user := make([]byte, int(header[1]))
					if _, err := io.ReadFull(c, user); err != nil {
						return
					}
					var length [1]byte
					if _, err := io.ReadFull(c, length[:]); err != nil {
						return
					}
					pass := make([]byte, int(length[0]))
					if _, err := io.ReadFull(c, pass); err != nil {
						return
					}
					status := byte(0)
					if header[0] != 1 || string(user) != username || string(pass) != password {
						status = 1
					}
					_, _ = c.Write([]byte{1, status})
					if status != 0 {
						return
					}
				}
				var request [3]byte
				if _, err := io.ReadFull(c, request[:]); err != nil {
					return
				}
				if request[0] != 5 || request[2] != 0 {
					t.Error("invalid command header")
					return
				}
				addr, err := S.ReadAddr(c, make([]byte, S.MaxAddrLen))
				if err != nil {
					return
				}
				server(c, S.Command(request[1]), addr)
			}()
		}
	}()
	host, port, _ := net.SplitHostPort(listener.Addr().String())
	p, _ := net.LookupPort("tcp", port)
	return config{Type: "SOCKS5", Host: "private.proxy.example", DialHost: host, Port: p, Username: username, Password: password, DoHURL: "https://dns.google/dns-query", AllowIPv6: true}
}

func TestSOCKS5TCP(t *testing.T) {
	for _, auth := range []bool{false, true} {
		t.Run(map[bool]string{false: "anonymous", true: "authenticated"}[auth], func(t *testing.T) {
			username, password := "", ""
			if auth {
				username, password = "private-user", "private-password"
			}
			cfg := socks5Fixture(t, username, password, func(c net.Conn, cmd S.Command, addr S.Addr) {
				if cmd != S.CmdConnect || addr.String() != "origin.private.example:443" {
					t.Error("destination must be resolved by proxy")
					return
				}
				_, _ = c.Write(append([]byte{5, 0, 0}, S.ParseAddrString("127.0.0.1:1")...))
				_, _ = io.Copy(c, c)
			})
			logs := &diagnosticRecorder{}
			stats := &connectionStats{sockets: make(map[*measuredTCPConn]struct{})}
			d := &socks5Dialer{config: cfg, protector: &jumpTestProtector{}, reporter: logs, stats: stats}
			defer d.Close()
			c, err := d.connectTarget(context.Background(), "origin.private.example:443")
			if err != nil {
				t.Fatal(err)
			}
			payload := bytes.Repeat([]byte("data"), 1024)
			if _, err := c.Write(payload); err != nil {
				t.Fatal(err)
			}
			reply := make([]byte, len(payload))
			if _, err := io.ReadFull(c, reply); err != nil || !bytes.Equal(reply, payload) {
				t.Fatalf("TCP echo: %v", err)
			}
			if stats.uploadBytes.Load() != uint64(len(payload)) || stats.downloadBytes.Load() != uint64(len(payload)) {
				t.Fatal("incorrect byte totals")
			}
			for _, private := range []string{cfg.Host, "origin.private.example", username, password} {
				if private != "" && strings.Contains(logs.text(), private) {
					t.Fatal("private data in diagnostics")
				}
			}
			_ = d.Close()
			_ = c.SetReadDeadline(time.Now().Add(time.Second))
			if _, err := c.Read(make([]byte, 1)); err == nil {
				t.Fatal("Stop did not close active TCP")
			}
			if _, err := d.connectTarget(context.Background(), "origin.private.example:443"); err == nil {
				t.Fatal("closed dialer accepted connection")
			}
		})
	}
}

func TestSOCKS5RejectsMalformedResponses(t *testing.T) {
	for _, tc := range []struct {
		name       string
		response   []byte
		user, pass string
	}{
		{"wrong_version", []byte{4, 0}, "", ""}, {"unoffered_method", []byte{5, 2}, "", ""},
		{"auth_downgrade", []byte{5, 0}, "u", "p"}, {"wrong_auth_version", []byte{5, 2, 2, 0}, "u", "p"},
		{"auth_rejection", []byte{5, 2, 1, 1}, "u", "p"},
		{"command_version", []byte{5, 0, 4, 0, 0}, "", ""}, {"reserved", []byte{5, 0, 5, 0, 1}, "", ""},
		{"command_rejection", []byte{5, 0, 5, 2, 0}, "", ""}, {"address_type", []byte{5, 0, 5, 0, 0, 9}, "", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			client, server := net.Pipe()
			defer client.Close()
			go func() { defer server.Close(); go io.Copy(io.Discard, server); _, _ = server.Write(tc.response) }()
			_ = client.SetDeadline(time.Now().Add(time.Second))
			if _, err := socks5Handshake(client, S.ParseAddrString("example.test:443"), S.CmdConnect, tc.user, tc.pass); err == nil {
				t.Fatal("accepted malformed or rejected response")
			}
		})
	}
}

func TestSOCKS5CancelAndStopHandshake(t *testing.T) {
	for _, stop := range []bool{false, true} {
		t.Run(map[bool]string{false: "cancel", true: "stop"}[stop], func(t *testing.T) {
			entered := make(chan struct{})
			cfg := socks5Fixture(t, "", "", func(c net.Conn, _ S.Command, _ S.Addr) { close(entered); _, _ = io.Copy(io.Discard, c) })
			d := &socks5Dialer{config: cfg, protector: &jumpTestProtector{}}
			defer d.Close()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			go func() { _, err := d.connectTarget(ctx, "example.test:443"); done <- err }()
			select {
			case <-entered:
			case <-time.After(time.Second):
				t.Fatal("handshake did not start")
			}
			if stop {
				_ = d.Close()
			} else {
				cancel()
			}
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("handshake succeeded")
				}
			case <-time.After(time.Second):
				t.Fatal("handshake did not stop")
			}
		})
	}
}

func TestSOCKS5UDP(t *testing.T) {
	relay, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	cfg := socks5Fixture(t, "u", "p", func(c net.Conn, cmd S.Command, addr S.Addr) {
		if cmd != S.CmdUDPAssociate || addr.String() != "0.0.0.0:0" {
			t.Error("invalid UDP ASSOCIATE")
			return
		}
		// Wildcard relay address must use the already bootstrapped proxy IP.
		_, port, _ := net.SplitHostPort(relay.LocalAddr().String())
		_, _ = c.Write(append([]byte{5, 0, 0}, S.ParseAddrString("0.0.0.0:"+port)...))
		_, _ = io.Copy(io.Discard, c)
	})
	go func() {
		packet := make([]byte, 65535)
		for {
			n, peer, err := relay.ReadFrom(packet)
			if err != nil {
				return
			}
			// Fragments, truncated headers and a packet for another IP must be dropped.
			_, _ = relay.WriteTo([]byte{0, 0, 1, 1, 127}, peer)
			_, _ = relay.WriteTo([]byte{0, 0, 0, 1}, peer)
			wrong, _ := S.EncodeUDPPacket(S.ParseAddrString("198.51.100.13:443"), []byte("wrong"))
			_, _ = relay.WriteTo(wrong, peer)
			_, _ = relay.WriteTo(packet[:n], peer)
		}
	}()
	stats := &connectionStats{sockets: make(map[*measuredTCPConn]struct{})}
	d := &socks5Dialer{config: cfg, protector: &jumpTestProtector{}, stats: stats}
	defer d.Close()
	m := &M.Metadata{DstIP: netip.MustParseAddr("198.51.100.12"), DstPort: 443}
	packet, err := d.DialUDP(m)
	if err != nil {
		t.Fatal(err)
	}
	_ = packet.SetDeadline(time.Now().Add(2 * time.Second))
	peer := &net.UDPAddr{IP: net.IP(m.DstIP.AsSlice()), Port: int(m.DstPort)}
	for _, size := range []int{0, 5, 1200, 1350, 4096} {
		payload := bytes.Repeat([]byte{42}, size)
		if n, err := packet.WriteTo(payload, peer); err != nil || n != size {
			t.Fatalf("UDP send n=%d err=%v", n, err)
		}
		b := make([]byte, size)
		if n, addr, err := packet.ReadFrom(b); err != nil || n != size || addr.String() != peer.String() || !bytes.Equal(b, payload) {
			t.Fatalf("UDP receive n=%d err=%v", n, err)
		}
	}
	if stats.uploadBytes.Load() != stats.downloadBytes.Load() || stats.uploadBytes.Load() != 6651 {
		t.Fatal("UDP headers counted as application traffic")
	}
	if _, err := packet.WriteTo([]byte{1}, &net.UDPAddr{IP: net.IPv4(1, 1, 1, 1), Port: 443}); err == nil {
		t.Fatal("changed UDP target accepted")
	}
	_ = d.Close()
	if _, err := packet.WriteTo([]byte{1}, peer); err == nil {
		t.Fatal("Stop did not close UDP")
	}
}

func TestSOCKS5ConfigAndPolicy(t *testing.T) {
	base := config{Type: "SOCKS5", Host: "example.test", DialHost: "127.0.0.1", Port: 1080, DoHURL: "https://dns.google/dns-query"}
	for _, tc := range []struct {
		user, pass string
		valid      bool
	}{
		{"", "", true}, {"u", "p", true}, {strings.Repeat("я", 127), "p", true}, {strings.Repeat("я", 128), "p", false},
		{"u", "", false}, {"", "p", false}, {"u", strings.Repeat("p", 256), false},
	} {
		cfg := base
		cfg.Username, cfg.Password = tc.user, tc.pass
		b, _ := json.Marshal(cfg)
		_, err := parseConfig(string(b))
		if (err == nil) != tc.valid {
			t.Fatalf("credentials validation valid=%t err=%v", tc.valid, err)
		}
	}
	d := &socks5Dialer{config: base, protector: &jumpTestProtector{}}
	for _, target := range []string{"[2001:db8::1]:443", strings.Repeat("x", 256) + ":443", "example:0", "example:65536"} {
		if err := d.checkTarget(target); err == nil {
			t.Fatal("invalid target accepted")
		}
	}
	dns, err := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr("198.51.100.1"), DstPort: 53})
	if err != nil {
		t.Fatal(err)
	}
	defer dns.Close()
	_ = d.Close()
}

func TestSOCKS5DNSUsesProxiedDoH(t *testing.T) {
	query, _, err := buildAQuery("private-origin.test")
	if err != nil {
		t.Fatal(err)
	}
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		payload, err := io.ReadAll(r.Body)
		if err != nil || !bytes.Equal(payload, query) {
			t.Error("unexpected DNS query")
			w.WriteHeader(400)
			return
		}
		binary.BigEndian.PutUint16(payload[2:4], 0x8180)
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(payload)
	}))
	defer origin.Close()
	_, port, _ := net.SplitHostPort(origin.Listener.Addr().String())
	cfg := socks5Fixture(t, "", "", func(c net.Conn, cmd S.Command, addr S.Addr) {
		if cmd != S.CmdConnect || addr.String() != net.JoinHostPort("example.com", port) {
			t.Error("DNS must use SOCKS5 TCP to the provider hostname")
			return
		}
		upstream, err := net.Dial("tcp", origin.Listener.Addr().String())
		if err != nil {
			t.Error(err)
			return
		}
		defer upstream.Close()
		_, _ = c.Write(append([]byte{5, 0, 0}, S.ParseAddrString("127.0.0.1:1")...))
		go func() { _, _ = io.Copy(upstream, c); _ = upstream.Close() }()
		_, _ = io.Copy(c, upstream)
	})
	cfg.DoHURL = "https://example.com:" + port + "/dns-query"
	d := &socks5Dialer{config: cfg, protector: &jumpTestProtector{}}
	defer d.Close()
	packet, err := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr("198.51.100.1"), DstPort: 53})
	if err != nil {
		t.Fatal(err)
	}
	defer packet.Close()
	// Trust only the disposable TLS origin; production still verifies DoH normally.
	d.dohClient.Transport.(*http.Transport).TLSClientConfig = origin.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
	_ = packet.SetDeadline(time.Now().Add(3 * time.Second))
	peer := &net.UDPAddr{IP: net.IPv4(198, 51, 100, 1), Port: 53}
	if _, err := packet.WriteTo(query, peer); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 4096)
	n, _, err := packet.ReadFrom(reply)
	if err != nil || n != len(query) || binary.BigEndian.Uint16(reply[2:4]) != 0x8180 {
		t.Fatalf("proxied DoH reply: n=%d err=%v", n, err)
	}
}

func TestSOCKS5LocalBypassAndSocketProtection(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		c, err := listener.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		_, _ = io.Copy(c, c)
	}()
	d := &socks5Dialer{config: config{BypassLocalNetworks: true}, protector: &jumpTestProtector{}}
	defer d.Close()
	c, err := d.connectTarget(context.Background(), listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(time.Second))
	_, _ = c.Write([]byte("bypass"))
	b := make([]byte, 6)
	if _, err := io.ReadFull(c, b); err != nil || string(b) != "bypass" {
		t.Fatal("local bypass failed", err)
	}
	blocked := &socks5Dialer{config: config{BypassLocalNetworks: true}, protector: &rejectingProtector{}}
	defer blocked.Close()
	if _, err := blocked.connectTarget(context.Background(), listener.Addr().String()); err == nil {
		t.Fatal("protect failure must fail closed")
	}
}

func TestSOCKS5TCPHalfClosePreservesReplyWithStatistics(t *testing.T) {
	cfg := socks5Fixture(t, "", "", func(c net.Conn, _ S.Command, _ S.Addr) {
		_, _ = c.Write(append([]byte{5, 0, 0}, S.ParseAddrString("127.0.0.1:1")...))
		payload, err := io.ReadAll(c)
		if err != nil {
			t.Error(err)
			return
		}
		_, _ = c.Write(append([]byte("reply:"), payload...))
	})
	d := &socks5Dialer{config: cfg, protector: &jumpTestProtector{}, stats: &connectionStats{sockets: make(map[*measuredTCPConn]struct{})}}
	defer d.Close()
	c, err := d.connectTarget(context.Background(), "[2001:db8::1]:443")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(time.Second))
	_, _ = c.Write([]byte("request"))
	if err := closeConnWrite(c); err != nil {
		t.Fatal(err)
	}
	reply, err := io.ReadAll(c)
	if err != nil || string(reply) != "reply:request" {
		t.Fatalf("half-close lost response: %q err=%v", reply, err)
	}
}

func TestSOCKS5InvalidRelayClosesControlSocket(t *testing.T) {
	for _, relay := range []string{"relay.invalid:1234", "127.0.0.1:0"} {
		t.Run(relay, func(t *testing.T) {
			cfg := socks5Fixture(t, "", "", func(c net.Conn, _ S.Command, _ S.Addr) {
				_, _ = c.Write(append([]byte{5, 0, 0}, S.ParseAddrString(relay)...))
				_, _ = io.Copy(io.Discard, c)
			})
			d := &socks5Dialer{config: cfg, protector: &jumpTestProtector{}}
			defer d.Close()
			if _, err := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr("198.51.100.1"), DstPort: 443}); err == nil {
				t.Fatal("invalid relay accepted")
			}
			d.mu.Lock()
			sockets := len(d.sockets)
			d.mu.Unlock()
			if sockets != 0 {
				t.Fatal("failed association leaked control socket")
			}
		})
	}
}

func TestSOCKS5ControlEOFClosesUDP(t *testing.T) {
	relay, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer relay.Close()
	cfg := socks5Fixture(t, "", "", func(c net.Conn, _ S.Command, _ S.Addr) {
		_, _ = c.Write(append([]byte{5, 0, 0}, S.ParseAddrString(relay.LocalAddr().String())...))
		// Return closes only the server-side TCP control socket.
	})
	d := &socks5Dialer{config: cfg, protector: &jumpTestProtector{}}
	defer d.Close()
	packet, err := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr("198.51.100.1"), DstPort: 443})
	if err != nil {
		t.Fatal(err)
	}
	defer packet.Close()
	_ = packet.SetDeadline(time.Now().Add(time.Second))
	if _, _, err := packet.ReadFrom(make([]byte, 1200)); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("control EOF must terminate association: %v", err)
	}
}

func TestSOCKS5BypassedTargetResetIsNotProxyFailure(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		c, err := listener.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		_ = c.(*net.TCPConn).SetLinger(0)
		_, _ = c.Read(make([]byte, 1))
	}()
	logs := &diagnosticRecorder{}
	d := &socks5Dialer{config: config{BypassLocalNetworks: true}, protector: &jumpTestProtector{}, reporter: logs}
	defer d.Close()
	c, err := d.connectTarget(context.Background(), listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(time.Second))
	_, _ = c.Write([]byte{1})
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Fatal("expected target reset")
	}
	if !strings.Contains(logs.text(), "scope=target") || strings.Contains(logs.text(), "scope=proxy") {
		t.Fatalf("local target must not trigger VPN failover: %s", logs.text())
	}
}
