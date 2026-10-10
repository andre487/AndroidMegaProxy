package mobile

import (
	"context"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/refraction-networking/uquic/http3"
)

// Real nested QUIC, with an unresolvable exit hostname: only the jump may reach it.
func http3JumpFixture(t *testing.T, exit config) config {
	target := &net.UDPAddr{IP: net.ParseIP(exit.DialHost), Port: exit.Port}
	jump := newMasqueFixture(t, nil, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
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
	})).config
	c := exit
	c.Type, c.PreferHTTP3, c.Host, c.DialHost = "HTTPS_JUMP", true, "exit.invalid", ""
	c.JumpHost, c.JumpDialHost, c.JumpPort = jump.Host, jump.DialHost, jump.Port
	c.JumpUsername, c.JumpPassword = "jump", "jump-password"
	c.JumpAllowInvalidProxyCertificate = true
	return c
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
			if _, err := packet.WriteTo([]byte(strings.Repeat("u", 1200)), peer); err != nil {
				t.Fatal(err)
			}
			buffer := make([]byte, 1300)
			n, _, err := packet.ReadFrom(buffer)
			if err != nil || string(buffer[:n]) != strings.Repeat("u", 1200) {
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
