package mobile

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"fmt"
	"golang.org/x/crypto/ssh"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"testing"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
)

// Loopback round trips include the production dialer, encryption and local peer.
// Reported bytes include request + response. They exclude TUN, JNI and Android.
func benchmarkStreamEcho(b *testing.B, conn net.Conn, size int) {
	b.Helper()
	b.Cleanup(func() { _ = conn.Close() })
	// SSH channels do not implement deadlines; a watchdog bounds all variants.
	watchdog := time.AfterFunc(time.Minute, func() { _ = conn.Close() })
	b.Cleanup(func() { watchdog.Stop() })
	_ = conn.SetDeadline(time.Now().Add(time.Minute))
	payload, reply := bytes.Repeat([]byte{0x5a}, size), make([]byte, size)
	// Warm the connection before starting the timer.
	exchange := func() {
		if n, err := conn.Write(payload); err != nil || n != size {
			b.Fatalf("write: n=%d err=%v", n, err)
		}
		if _, err := io.ReadFull(conn, reply); err != nil {
			b.Fatal(err)
		}
	}
	exchange()
	b.SetBytes(int64(2 * size))
	b.ReportAllocs()
	for b.Loop() {
		exchange()
	}
	if !bytes.Equal(payload, reply) {
		b.Fatal("echo payload changed")
	}
}

func BenchmarkTransportMASQUE(b *testing.B) {
	for _, size := range []int{64, 1400, 32768} {
		b.Run(fmt.Sprintf("stream/bytes=%d", size), func(b *testing.B) {
			fixture := newMasqueFixture(b, nil)
			d := &masqueDialer{config: fixture.config, protector: &jumpTestProtector{}, stats: &connectionStats{}}
			b.Cleanup(func() { _ = d.Close() })
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			conn, err := d.connectTarget(ctx, "origin.test:443")
			if err != nil {
				b.Fatal(err)
			}
			benchmarkStreamEcho(b, conn, size)
		})
	}
	for _, size := range []int{64, 1200} {
		for _, refreshDeadline := range []bool{false, true} {
			b.Run(fmt.Sprintf("datagram/bytes=%d/refresh-deadline=%t", size, refreshDeadline), func(b *testing.B) {
				fixture := newMasqueFixture(b, nil)
				d := &masqueDialer{config: fixture.config, protector: &jumpTestProtector{}, stats: &connectionStats{}}
				b.Cleanup(func() { _ = d.Close() })
				conn, err := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr("192.0.2.1"), DstPort: 443})
				if err != nil {
					b.Fatal(err)
				}
				b.Cleanup(func() { _ = conn.Close() })
				peer := &net.UDPAddr{IP: net.IPv4(192, 0, 2, 1), Port: 443}
				payload, reply := bytes.Repeat([]byte{0x5a}, size), make([]byte, size)
				expectedPeer := conn.(*masquePacketConn).remote
				_ = conn.SetDeadline(time.Now().Add(time.Minute))
				exchange := func() {
					if refreshDeadline {
						_ = conn.SetDeadline(time.Now().Add(3 * time.Second))
					}
					if n, err := conn.WriteTo(payload, peer); err != nil || n != size {
						b.Fatalf("write: n=%d err=%v", n, err)
					}
					if n, from, err := conn.ReadFrom(reply); err != nil || n != size || from != expectedPeer {
						b.Fatalf("read: n=%d from=%v err=%v", n, from, err)
					}
				}
				exchange()
				b.SetBytes(int64(2 * size))
				b.ReportAllocs()
				for b.Loop() {
					exchange()
				}
				if !bytes.Equal(payload, reply) {
					b.Fatal("echo payload changed")
				}
			})
		}
	}
}

func BenchmarkTransportHTTPS(b *testing.B) {
	for _, protocol := range []string{"http1", "http2", "http3"} {
		for _, size := range []int{64, 1400, 32768} {
			b.Run(fmt.Sprintf("%s/bytes=%d", protocol, size), func(b *testing.B) {
				var cfg config
				if protocol == "http3" {
					cfg = newMasqueFixture(b, nil).config
					cfg.Type = "HTTPS"
					cfg.PreferHTTP3 = true
				} else {
					server := jumpTestProxy(b, protocol == "http2", "origin.test:443", "user:password", "", false, time.Minute)
					host, port, _ := net.SplitHostPort(server.Listener.Addr().String())
					number, _ := strconv.Atoi(port)
					cfg = config{Type: "HTTPS", Host: host, DialHost: host, Port: number, Username: "user", Password: "password", Profile: "CHROME_ANDROID", AllowInvalidProxyCertificate: true}
				}
				ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
				defer cancel()
				stats := &connectionStats{sockets: make(map[*measuredTCPConn]struct{})}
				var conn net.Conn
				if protocol == "http3" {
					d, err := preferredHTTP3(ctx, cfg, &jumpTestProtector{}, nil, stats)
					if err != nil || d == nil {
						b.Fatalf("HTTP/3 selection failed: %v", err)
					}
					b.Cleanup(func() { _ = d.Close() })
					conn, err = d.connectTarget(ctx, "origin.test:443")
					if err != nil {
						b.Fatal(err)
					}
				} else {
					d := &httpsConnectDialer{config: cfg, protector: &jumpTestProtector{}, stats: stats}
					b.Cleanup(func() { _ = d.Close() })
					var err error
					conn, err = d.connectTarget(ctx, "origin.test:443")
					if err != nil {
						b.Fatal(err)
					}
					d.cacheMu.Lock()
					h2 := d.h2Session != nil
					d.cacheMu.Unlock()
					if h2 != (protocol == "http2") {
						b.Fatal("unexpected negotiated HTTP protocol")
					}
				}
				benchmarkStreamEcho(b, conn, size)
			})
		}
	}
}

// Real encrypted direct-tcpip channels with disposable credentials and pinned host key.
// The local SSH peer echoes inside each channel, without dialing any origin.
func transportSSHFixture(t testing.TB) config {
	t.Helper()
	_, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.NewSignerFromKey(private)
	if err != nil {
		t.Fatal(err)
	}
	serverConfig := &ssh.ServerConfig{PasswordCallback: func(c ssh.ConnMetadata, password []byte) (*ssh.Permissions, error) {
		if c.User() != "user" || string(password) != "password" {
			return nil, errors.New("invalid fixture credentials")
		}
		return nil, nil
	}}
	serverConfig.AddHostKey(signer)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	connections := make(map[net.Conn]struct{})
	var workers sync.WaitGroup
	acceptDone := make(chan struct{})
	go func() {
		defer close(acceptDone)
		for {
			raw, err := listener.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			connections[raw] = struct{}{}
			mu.Unlock()
			workers.Add(1)
			go func() {
				defer workers.Done()
				defer func() { _ = raw.Close(); mu.Lock(); delete(connections, raw); mu.Unlock() }()
				_ = raw.SetDeadline(time.Now().Add(10 * time.Second))
				server, channels, requests, err := ssh.NewServerConn(raw, serverConfig)
				if err != nil {
					return
				}
				defer server.Close()
				_ = raw.SetDeadline(time.Time{})
				go ssh.DiscardRequests(requests)
				for request := range channels {
					var target struct {
						Host       string
						Port       uint32
						Origin     string
						OriginPort uint32
					}
					if request.ChannelType() != "direct-tcpip" || ssh.Unmarshal(request.ExtraData(), &target) != nil || target.Host != "origin.test" || target.Port != 443 {
						_ = request.Reject(ssh.Prohibited, "fixture target denied")
						continue
					}
					channel, requests, err := request.Accept()
					if err != nil {
						continue
					}
					go ssh.DiscardRequests(requests)
					workers.Add(1)
					go func() { defer workers.Done(); defer channel.Close(); _, _ = io.Copy(channel, channel) }()
				}
			}()
		}
	}()
	t.Cleanup(func() {
		_ = listener.Close()
		<-acceptDone
		mu.Lock()
		for raw := range connections {
			_ = raw.Close()
		}
		mu.Unlock()
		workers.Wait()
	})
	host, port, _ := net.SplitHostPort(listener.Addr().String())
	number, _ := strconv.Atoi(port)
	return config{Type: "SSH", Host: host, DialHost: host, Port: number, Username: "user", Password: "password", TrustedHostKey: ssh.FingerprintSHA256(signer.PublicKey()), SSHProfile: "OPENSSH_TERMUX", SSHAuthMode: "PASSWORD_ONLY", SSHMaxChannels: 32}
}

func BenchmarkTransportSSH(b *testing.B) {
	for _, size := range []int{64, 1400, 32768} {
		b.Run(fmt.Sprintf("stream/bytes=%d", size), func(b *testing.B) {
			cfg := transportSSHFixture(b)
			d := &sshDialer{config: cfg, protector: &jumpTestProtector{}, stats: &connectionStats{sockets: make(map[*measuredTCPConn]struct{})}}
			b.Cleanup(func() { _ = d.Close() })
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			conn, err := d.connectTarget(ctx, "origin.test:443")
			if err != nil {
				b.Fatal(err)
			}
			benchmarkStreamEcho(b, conn, size)
		})
	}
}

func TestTransportSSHFixtureRejectsWrongHostKeyAndTarget(t *testing.T) {
	for _, failure := range []string{"host-key", "target", "password", "none"} {
		t.Run(failure, func(t *testing.T) {
			cfg := transportSSHFixture(t)
			if failure == "host-key" {
				cfg.TrustedHostKey = "incorrect"
			}
			if failure == "password" {
				cfg.Password = "incorrect"
			}
			d := &sshDialer{config: cfg, protector: &jumpTestProtector{}}
			defer d.Close()
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			target := "origin.test:443"
			if failure == "target" {
				target = "untrusted.test:443"
			}
			conn, err := d.connectTarget(ctx, target)
			if failure != "none" {
				if err == nil {
					_ = conn.Close()
					t.Fatal("untrusted connection accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			watchdog := time.AfterFunc(3*time.Second, func() { _ = conn.Close() })
			defer watchdog.Stop()
			if _, err := conn.Write([]byte("echo")); err != nil {
				t.Fatal(err)
			}
			var reply [4]byte
			if _, err := io.ReadFull(conn, reply[:]); err != nil || string(reply[:]) != "echo" {
				t.Fatalf("echo: %v", err)
			}
		})
	}
}
