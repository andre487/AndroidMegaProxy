package mobile

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/refraction-networking/uquic/http3"
)

// Real nested QUIC, with an unresolvable exit hostname: only the jump may reach it.
func http3JumpFixture(t *testing.T, exit config) config {
	c, _ := http3JumpTransportFixture(t, exit, nil)
	return c
}

func http3JumpTransportFixture(t *testing.T, exit config, wrap func(net.PacketConn) net.PacketConn) (config, masqueFixture) {
	target := &net.UDPAddr{IP: net.ParseIP(exit.DialHost), Port: exit.Port}
	jump := newMasqueFixture(t, wrap, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "OPTIONS" {
			w.WriteHeader(405)
			return
		}
		if r.Header.Get("Proxy-Authorization") != "Basic "+base64.StdEncoding.EncodeToString([]byte("jump:jump-password")) {
			w.WriteHeader(407)
			return
		}
		if r.Proto != "connect-udp" || !strings.Contains(r.URL.Path, "exit.invalid") {
			w.WriteHeader(400)
			return
		}
		packet, err := net.DialUDP("udp", nil, target)
		if err != nil {
			w.WriteHeader(502)
			return
		}
		defer packet.Close()
		w.Header().Set("Capsule-Protocol", "?1")
		w.WriteHeader(200)
		stream := w.(http3.HTTPStreamer).HTTPStream()
		defer stream.Close()
		stop := context.AfterFunc(stream.Context(), func() { packet.Close() })
		defer stop()
		done := make(chan struct{})
		go func() {
			defer close(done)
			buffer := make([]byte, 2048)
			for {
				n, err := packet.Read(buffer)
				if err != nil {
					return
				}
				if err := stream.SendDatagram(append([]byte{0}, buffer[:n]...)); err != nil {
					return
				}
			}
		}()
		for {
			data, err := stream.ReceiveDatagram(r.Context())
			if err != nil {
				break
			}
			if len(data) > 0 && data[0] == 0 {
				if _, err := packet.Write(data[1:]); err != nil {
					break
				}
			}
		}
		packet.Close()
		<-done
	}))
	c := exit
	c.Type, c.PreferHTTP3, c.Host, c.DialHost = "HTTPS_JUMP", true, "exit.invalid", ""
	c.JumpHost, c.JumpDialHost, c.JumpPort = jump.config.Host, jump.config.DialHost, jump.config.Port
	c.JumpUsername, c.JumpPassword = "jump", "jump-password"
	c.JumpAllowInvalidProxyCertificate = true
	return c, jump
}

func TestHTTPSHTTP3JumpNestedTrafficAndSecurity(t *testing.T) {
	exit := masqueTestServer(t)
	for _, name := range []string{"traffic", "jump_password", "exit_password", "jump_certificate", "exit_certificate", "firefox_mtu"} {
		t.Run(name, func(t *testing.T) {
			c := http3JumpFixture(t, exit)
			switch name {
			case "jump_password":
				c.JumpPassword = "wrong"
			case "exit_password":
				c.Password = "wrong"
			case "jump_certificate":
				c.JumpAllowInvalidProxyCertificate = false
			case "exit_certificate":
				c.AllowInvalidProxyCertificate = false
			case "firefox_mtu":
				c.Profile = "FIREFOX_ANDROID"
			}
			logs := &diagnosticRecorder{}
			d, err := preferredHTTP3(context.Background(), c, &jumpTestProtector{}, logs, nil)
			if name == "firefox_mtu" {
				if d != nil {
					d.Close()
					t.Fatal("Firefox selected undersized UDP tunnel")
				}
				if err != nil || !strings.Contains(logs.text(), "reason=unsupported_datagram_size") {
					t.Fatalf("%v: %s", err, logs.text())
				}
				return
			}
			if name == "jump_password" || strings.HasSuffix(name, "certificate") {
				if d != nil {
					d.Close()
					t.Fatal("invalid hop accepted")
				}
				if err == nil || strings.Contains(logs.text(), "result=fallback") {
					t.Fatalf("security failure downgraded: %v %s", err, logs.text())
				}
				hop, reason, stage := "jump", "proxy_authentication", "connect_udp"
				if strings.HasSuffix(name, "certificate") {
					reason, stage = "certificate", "tls_handshake"
				}
				if name == "exit_certificate" {
					hop = "destination"
				}
				for _, field := range []string{"reason=" + reason, "hop=" + hop, "stage=" + stage} {
					if !strings.Contains(logs.text(), field) {
						t.Fatalf("missing %s: %s", field, logs.text())
					}
				}
				for _, private := range []string{c.Host, c.JumpHost, c.JumpPassword, c.Password, "x509"} {
					if strings.Contains(logs.text(), private) {
						t.Fatalf("private failure diagnostic: %s", logs.text())
					}
				}
				return
			}
			if err != nil || d == nil {
				t.Fatalf("nested H3 unavailable: %v %s", err, logs.text())
			}
			defer d.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			conn, err := d.connectTarget(ctx, "target.example:443")
			if name == "exit_password" {
				if conn != nil || errorClass(err) != "proxy_authentication" {
					t.Fatalf("exit auth accepted: %v", err)
				}
				if strings.Contains(logs.text(), "result=fallback") {
					t.Fatal("exit authentication downgraded")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			conn.SetDeadline(time.Now().Add(3 * time.Second))
			payload := strings.Repeat("nested", 1000)
			if _, err := conn.Write([]byte(payload)); err != nil {
				t.Fatal(err)
			}
			reply := make([]byte, len(payload))
			if _, err := io.ReadFull(conn, reply); err != nil || string(reply) != payload {
				t.Fatalf("nested echo: %v", err)
			}
			stream, err := d.openTunnel(ctx, "target.example:8081", true)
			if err != nil {
				t.Fatal(err)
			}
			peer := &net.UDPAddr{IP: net.IPv4(192, 0, 2, 2), Port: 8081}
			packet := d.udpPacketConn(stream, peer)
			defer packet.Close()
			packet.SetDeadline(time.Now().Add(3 * time.Second))
			if _, err := packet.WriteTo([]byte(strings.Repeat("u", 1350)), peer); err != nil {
				t.Fatal(err)
			}
			buffer := make([]byte, 1400)
			n, _, err := packet.ReadFrom(buffer)
			if err != nil || string(buffer[:n]) != strings.Repeat("u", 1350) {
				t.Fatalf("nested UDP: %v", err)
			}
			// The first-hop packet reader retains the startup reporter. Runtime
			// failures must reach the live sink after transport selection.
			d.jump.config.Password = "wrong"
			_, err = d.jump.openTunnel(ctx, "exit.invalid:443", true)
			if errorClass(err) != "proxy_authentication" || !strings.Contains(logs.text(), "status=407") {
				t.Fatalf("jump runtime diagnostic lost: %v %s", err, logs.text())
			}
			for _, private := range []string{"exit.invalid", "jump-password", "target.example", strconv.Itoa(c.JumpPort)} {
				if strings.Contains(logs.text(), private) {
					t.Fatalf("private data in logs: %s", private)
				}
			}
		})
	}
}

func TestHTTPSJumpUnsupportedUDPAndAccessFailures(t *testing.T) {
	for _, status := range []int{200, 404, 405, 501, 401, 403, 407, 500, 502} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			exit := masqueTestServer(t)
			jump := newMasqueFixture(t, nil, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method == "OPTIONS" {
					w.WriteHeader(405)
					return
				}
				w.WriteHeader(status)
			})).config
			c := exit
			c.Type, c.PreferHTTP3 = "HTTPS_JUMP", true
			c.JumpHost, c.JumpDialHost, c.JumpPort = jump.Host, jump.DialHost, jump.Port
			c.JumpUsername, c.JumpPassword = jump.Username, jump.Password
			c.JumpAllowInvalidProxyCertificate = true
			logs := &diagnosticRecorder{}
			d, err := preferredHTTP3(context.Background(), c, &jumpTestProtector{}, logs, nil)
			if d != nil {
				d.Close()
				t.Fatal("unsupported jump selected")
			}
			fallback := status == 200 || status == 404 || status == 405 || status == 501
			if (err == nil) != fallback || strings.Contains(logs.text(), "result=fallback") != fallback {
				t.Fatalf("status=%d error=%v logs=%s", status, err, logs.text())
			}
			if strings.Contains(logs.text(), "dpi_hint=possible") {
				t.Fatal("optional probe triggered blocking detection")
			}
			if !fallback {
				for _, field := range []string{fmt.Sprintf("status=%d", status), "hop=jump", "stage=connect_udp"} {
					if !strings.Contains(logs.text(), field) {
						t.Fatalf("missing %s: %s", field, logs.text())
					}
				}
			}
		})
	}
}

func TestHTTPSJumpRecoversAfterOuterSessionLoss(t *testing.T) {
	c := http3JumpFixture(t, masqueTestServer(t))
	logs := &diagnosticRecorder{}
	d, err := preferredHTTP3(context.Background(), c, &jumpTestProtector{}, logs, nil)
	if err != nil || d == nil {
		t.Fatal(err)
	}
	defer d.Close()
	old := d.session
	d.jump.session.conn.CloseWithError(0, "")
	select {
	case <-old.conn.Context().Done():
	case <-time.After(time.Second):
		t.Fatal("outer loss did not end inner session")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, err := d.connectTarget(ctx, "target.example:443")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	assertJumpEcho(t, conn)
	if d.session == old || strings.Contains(logs.text(), "result=fallback") {
		t.Fatal("wrong recovery transport")
	}
}

func assertJumpEcho(t *testing.T, conn net.Conn) {
	t.Helper()
	conn.SetDeadline(time.Now().Add(time.Second))
	if _, err := conn.Write([]byte("echo")); err != nil {
		t.Fatal(err)
	}
	b := make([]byte, 4)
	if _, err := io.ReadFull(conn, b); err != nil || string(b) != "echo" {
		t.Fatalf("echo: %q %v", b, err)
	}
}

// Independent incoming/outgoing ceilings exercise asymmetric MTU black holes.
type jumpLimitedPacketConn struct {
	net.PacketConn
	readMax, writeMax int
	dropFirst         atomic.Bool
}

func (p *jumpLimitedPacketConn) SyscallConn() (syscall.RawConn, error) {
	return p.PacketConn.(syscall.Conn).SyscallConn()
}
func (p *jumpLimitedPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	for {
		n, peer, err := p.PacketConn.ReadFrom(b)
		if err != nil || p.readMax == 0 || n <= p.readMax {
			return n, peer, err
		}
	}
}
func (p *jumpLimitedPacketConn) WriteTo(b []byte, peer net.Addr) (int, error) {
	if p.dropFirst.CompareAndSwap(true, false) || (p.writeMax > 0 && len(b) > p.writeMax) {
		return len(b), nil
	}
	return p.PacketConn.WriteTo(b, peer)
}

func TestHTTPSJumpAsymmetricMTUAndPacketLoss(t *testing.T) {
	for _, name := range []string{"incoming_mtu", "outgoing_mtu", "browser_reply_mtu", "lost_jump_initial", "lost_exit_initial"} {
		t.Run(name, func(t *testing.T) {
			wrap := func(conn net.PacketConn) net.PacketConn {
				p := &jumpLimitedPacketConn{PacketConn: conn}
				switch name {
				case "incoming_mtu":
					p.readMax = 1350
				case "outgoing_mtu":
					p.writeMax = 1350
				case "browser_reply_mtu":
					// The inner peer converges below the conservative GOST
					// reply budget for a 1350-byte browser packet.
					p.readMax = 1430
				case "lost_jump_initial", "lost_exit_initial":
					p.dropFirst.Store(true)
				}
				return p
			}
			var exit masqueFixture
			if name == "lost_exit_initial" {
				exit = newMasqueFixture(t, wrap)
			} else {
				exit = newMasqueFixture(t, nil)
			}
			var c config
			if name == "lost_exit_initial" {
				c = http3JumpFixture(t, exit.config)
			} else {
				c, _ = http3JumpTransportFixture(t, exit.config, wrap)
			}
			logs := &diagnosticRecorder{}
			d, err := preferredHTTP3(context.Background(), c, &jumpTestProtector{}, logs, nil)
			if strings.HasSuffix(name, "_mtu") {
				if d != nil {
					d.Close()
					t.Fatal("insufficient MTU selected")
				}
				if err != nil || !strings.Contains(logs.text(), "result=fallback") {
					t.Fatalf("MTU fallback: %v %s", err, logs.text())
				}
				return
			}
			// A lost Initial can inflate the peer's RTT and hence the PMTU
			// probe interval beyond our three-second selection budget. Both
			// successful H3 and bounded whole-chain HTTPS fallback are valid.
			if err == nil && d == nil && strings.Contains(logs.text(), "result=fallback reason=timeout") {
				return
			}
			if err != nil || d == nil {
				t.Fatalf("single loss broke selection: %v %s", err, logs.text())
			}
			defer d.Close()
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			conn, err := d.connectTarget(ctx, "target.example:443")
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			assertJumpEcho(t, conn)
		})
	}
}

func TestHTTPSJumpCancellationDuringExitHandshake(t *testing.T) {
	blackhole, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer blackhole.Close()
	cfg := masqueTestServer(t)
	cfg.DialHost, cfg.Port = "127.0.0.1", blackhole.LocalAddr().(*net.UDPAddr).Port
	c := http3JumpFixture(t, cfg)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	result := make(chan error, 1)
	logs := &diagnosticRecorder{}
	go func() {
		d, err := preferredHTTP3(ctx, c, &jumpTestProtector{}, logs, nil)
		if d != nil {
			d.Close()
		}
		result <- err
	}()
	blackhole.SetReadDeadline(time.Now().Add(time.Second))
	b := make([]byte, 2048)
	if _, _, err := blackhole.ReadFrom(b); err != nil {
		t.Fatal("exit handshake did not begin", err)
	}
	cancel()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancellation blocked")
	}
	if strings.Contains(logs.text(), "result=fallback") || strings.Contains(logs.text(), "result=selected") {
		t.Fatal("cancelled selection committed")
	}
}

func TestHTTPSJumpGoAwayPreservesActiveStreams(t *testing.T) {
	for _, hop := range []string{"jump", "destination", "both"} {
		t.Run(hop, func(t *testing.T) {
			exit := newMasqueFixture(t, nil)
			c, jump := http3JumpTransportFixture(t, exit.config, nil)
			d, err := preferredHTTP3(context.Background(), c, &jumpTestProtector{}, nil, nil)
			if err != nil || d == nil {
				t.Fatal(err)
			}
			defer d.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			first, err := d.connectTarget(ctx, "target.example:443")
			if err != nil {
				t.Fatal(err)
			}
			defer first.Close()
			oldExit, oldJump := d.session, d.jump.session
			var shutdowns []<-chan error
			retire := func(f masqueFixture, s *masqueSession) {
				done := make(chan error, 1)
				shutdowns = append(shutdowns, done)
				go func() { done <- f.server.Shutdown(ctx) }()
				select {
				case <-f.serving:
				case <-ctx.Done():
					t.Fatal("server did not drain")
				}
				replacement := &http3.Server{EnableDatagrams: true, Handler: f.server.Handler}
				go func() { _ = replacement.ServeListener(f.listener) }()
				t.Cleanup(func() { replacement.Close() })
				for s.client.CanTakeNewRequest() {
					select {
					case <-ctx.Done():
						t.Fatal("GOAWAY not received")
					case <-time.After(time.Millisecond):
					}
				}
			}
			if hop != "destination" {
				retire(jump, oldJump)
			}
			if hop != "jump" {
				retire(exit, oldExit)
			}
			second, err := d.connectTarget(ctx, "target.example:443")
			if err != nil {
				t.Fatal(err)
			}
			defer second.Close()
			if hop != "jump" && d.session == oldExit {
				t.Fatal("retired exit reused")
			}
			if hop == "both" && d.jump.session == oldJump {
				t.Fatal("retired jump reused")
			}
			if hop == "jump" && d.session != oldExit {
				t.Fatal("outer GOAWAY interrupted healthy inner QUIC")
			}
			assertJumpEcho(t, first)
			assertJumpEcho(t, second)
			newExit, newJump := d.session, d.jump.session
			d.Close()
			for _, s := range []*masqueSession{oldExit, oldJump, newExit, newJump} {
				if s != nil {
					select {
					case <-s.conn.Context().Done():
					case <-time.After(time.Second):
						t.Fatal("draining session leaked")
					}
				}
			}
			// CONNECTION_CLOSE is best effort, especially inside a UDP association
			// that we have already closed. Stop guarantees local resource cleanup;
			// explicitly end the remote fixtures instead of assuming delivery.
			if hop != "destination" {
				if err := jump.server.Close(); err != nil {
					t.Fatal(err)
				}
			}
			if hop != "jump" {
				if err := exit.server.Close(); err != nil {
					t.Fatal(err)
				}
			}
			for _, done := range shutdowns {
				select {
				case err := <-done:
					if err != nil {
						t.Fatal(err)
					}
				case <-time.After(time.Second):
					t.Fatal("draining server leaked")
				}
			}
		})
	}
}

func TestHTTPSJumpFallbackRetainsHopSecurity(t *testing.T) {
	for _, failure := range []string{"none", "jump_certificate", "exit_certificate", "jump_forbidden", "exit_forbidden"} {
		t.Run(failure, func(t *testing.T) {
			exit := jumpTestProxy(t, true, "site.invalid:443", "exit:exit-password", "", failure == "exit_forbidden")
			jump := jumpTestProxy(t, true, "exit.invalid:443", "jump:jump-password", exit.Listener.Addr().String(), failure == "jump_forbidden")
			host, port, _ := net.SplitHostPort(jump.Listener.Addr().String())
			number, _ := strconv.Atoi(port)
			// Dual TCP/H3 endpoint: only CONNECT-UDP is unavailable.
			newMasqueFixture(t, func(old net.PacketConn) net.PacketConn {
				old.Close()
				packet, err := net.ListenPacket("udp4", jump.Listener.Addr().String())
				if err != nil {
					t.Fatal(err)
				}
				return packet
			}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method == "OPTIONS" {
					w.WriteHeader(405)
				} else {
					w.WriteHeader(501)
				}
			}))
			c := config{Type: "HTTPS_JUMP", PreferHTTP3: true, Host: "exit.invalid", DialHost: "192.0.2.1", Port: 443,
				Username: "exit", Password: "exit-password", JumpHost: "jump.invalid", JumpDialHost: host, JumpPort: number,
				JumpUsername: "jump", JumpPassword: "jump-password", Profile: "CHROME_ANDROID",
				AllowInvalidProxyCertificate: failure != "exit_certificate", JumpAllowInvalidProxyCertificate: failure != "jump_certificate"}
			// Unsupported QUIC still must enforce the jump's TCP certificate policy.
			if failure == "jump_certificate" {
				c.Profile = "EDGE_ANDROID"
			}
			protector := &jumpTestProtector{}
			logs := &diagnosticRecorder{}
			preferred, err := preferredHTTP3(context.Background(), c, protector, logs, nil)
			if preferred != nil {
				preferred.Close()
				t.Fatal("unavailable UDP selected")
			}
			if err != nil || !strings.Contains(logs.text(), "result=fallback") {
				t.Fatalf("fallback: %v %s", err, logs.text())
			}
			d := &httpsConnectDialer{config: c, protector: protector, reporter: logs}
			defer d.Close()
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			defer cancel()
			conn, err := d.connectTarget(ctx, "site.invalid:443")
			if failure == "none" {
				if err != nil {
					t.Fatal(err)
				}
				defer conn.Close()
				assertJumpEcho(t, conn)
			} else {
				if conn != nil {
					conn.Close()
					t.Fatal("failed hop accepted")
				}
				if err == nil {
					t.Fatal("hop failure ignored")
				}
				if strings.HasSuffix(failure, "certificate") && errorClass(err) != "certificate" {
					t.Fatal(err)
				}
			}
			expectedSockets := int32(2)
			if failure == "jump_certificate" {
				expectedSockets = 1
			}
			if protector.calls.Load() != expectedSockets {
				t.Fatal("fallback opened unexpected direct sockets", protector.calls.Load())
			}
		})
	}
}
