//go:build integration

package mobile

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os/exec"
	"strconv"
	"strings"
	"testing"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"golang.org/x/crypto/ssh"
)

const gostTestImage = "gogost/gost:3.3.0@sha256:f7a958c451928fbe1b99046d25bd0c9bf42019d4c60a82200f50dc8e617b7580"

// These tests use the production dialers against independent server processes.
// The origin has no published port and its name exists only in Docker DNS.
func TestRealProxyServers(t *testing.T) {
	if _, err := exec.LookPath("docker"); err != nil {
		t.Fatal("integration tests require Docker (no silent skip):", err)
	}
	id := fmt.Sprintf("megaproxy-test-%d", time.Now().UnixNano())
	originAddress := id + "-origin:8080"
	image := id + ":fixture"
	dockerTest(t, "info", "--format", "{{.ServerVersion}}")
	t.Log("Building OpenSSH/HTTP fixture and starting real proxy servers")
	dockerTest(t, "build", "-t", image, "../integration")
	t.Cleanup(func() { dockerTest(t, "image", "rm", image) })
	dockerTest(t, "network", "create", id)
	t.Cleanup(func() { dockerTest(t, "network", "rm", id) })

	start := func(alias, port, serverImage string, args ...string) string {
		t.Helper()
		name := id + "-" + alias
		command := []string{"run", "-d", "--name", name, "--network", id, "--network-alias", alias,
			"--label", "net.megaproxy487.integration=true"}
		if port == "8443/both" {
			tcp, err := net.Listen("tcp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			udp, err := net.ListenPacket("udp4", tcp.Addr().String())
			if err != nil {
				tcp.Close()
				t.Fatal(err)
			}
			address := tcp.Addr().String()
			tcp.Close()
			udp.Close()
			command = append(command, "-p", address+":8443/tcp", "-p", address+":8443/udp")
		} else if port != "" {
			command = append(command, "-p", "127.0.0.1::"+port)
		}
		if strings.HasPrefix(alias, "ssh") {
			command = append(command, "-e", "TEST_PASSWORD="+alias+"-test-password")
		}
		command = append(command, serverImage)
		command = append(command, args...)
		t.Cleanup(func() {
			if t.Failed() {
				ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
				defer cancel()
				logs, err := exec.CommandContext(ctx, "docker", "logs", name).CombinedOutput()
				t.Logf("%s logs (%v):\n%s", alias, err, logs)
			}
			dockerTest(t, "rm", "-f", name)
		})
		dockerTest(t, command...)
		return name
	}
	start("origin", "", image, "python3", "/fixture/origin.py")
	h1 := start("gost-h1", "8443", gostTestImage, "-L", "http+tls://exit:exit-test-password@:8443")
	h2 := start("gost-h2", "8443", gostTestImage, "-L", "http2://jump:jump-test-password@:8443")
	masque := start("gost-masque", "8443/udp", gostTestImage, "-L", "masque+http3://exit:exit-test-password@:8443?enableDatagrams=true")
	fallbackHTTPS := start("gost-fallback", "8443", gostTestImage, "-L", "http+tls://exit:exit-test-password@:8443")
	dualJump := start("gost-dual-jump", "8443/both", gostTestImage, "-L", "http2://jump:jump-test-password@:8443", "-L", "masque+http3://jump:jump-test-password@:8443?enableDatagrams=true")
	dualExit := start("gost-dual-exit", "8443/both", gostTestImage, "-L", "http2://exit:exit-test-password@:8443", "-L", "masque+http3://exit:exit-test-password@:8443?enableDatagrams=true")
	sshExit := start("ssh-exit", "2222", image)
	sshJump := start("ssh-jump", "2222", image)

	endpoint := func(name, port string) (string, int) {
		t.Helper()
		address := strings.TrimSpace(dockerTest(t, "port", name, port+"/tcp"))
		host, p, err := net.SplitHostPort(address)
		if err != nil || host != "127.0.0.1" {
			t.Fatalf("expected loopback-only published port, got %q: %v", address, err)
		}
		number, err := strconv.Atoi(p)
		if err != nil {
			t.Fatal(err)
		}
		deadline := time.Now().Add(20 * time.Second)
		for {
			conn, err := net.DialTimeout("tcp", address, time.Second)
			if err == nil {
				_ = conn.Close()
				return host, number
			}
			if time.Now().After(deadline) {
				t.Fatalf("%s did not start: %v", name, err)
			}
			time.Sleep(100 * time.Millisecond)
		}
	}
	base := config{Type: "HTTPS", Host: "localhost", Username: "exit", Password: "exit-test-password",
		Profile: "CHROME_ANDROID", AllowInvalidProxyCertificate: true, DoHURL: "https://dns.google/dns-query"}
	base.DialHost, base.Port = endpoint(h1, "8443")
	h2Config := base
	h2Config.DialHost, h2Config.Port = endpoint(h2, "8443")
	h2Config.Username, h2Config.Password = "jump", "jump-test-password"
	masqueConfig := base
	masqueConfig.Type = "MASQUE"
	masquePublished := strings.TrimSpace(dockerTest(t, "port", masque, "8443/udp"))
	masqueConfig.DialHost, _, _ = net.SplitHostPort(masquePublished)
	_, masquePort, _ := net.SplitHostPort(masquePublished)
	masqueConfig.Port, _ = strconv.Atoi(masquePort)
	if masqueConfig.DialHost != "127.0.0.1" || masqueConfig.Port == 0 {
		t.Fatal("invalid MASQUE published port")
	}
	preferredMasque := masqueConfig
	preferredMasque.Type, preferredMasque.PreferHTTP3 = "HTTPS", true
	preferredHTTPS := base
	preferredHTTPS.PreferHTTP3 = true
	preferredHTTPS.DialHost, preferredHTTPS.Port = endpoint(fallbackHTTPS, "8443")
	preferredH2 := h2Config
	preferredH2.PreferHTTP3 = true
	masqueFirefox := masqueConfig
	masqueFirefox.Profile = "FIREFOX_ANDROID"
	masqueCustom := masqueConfig
	masqueCustom.Profile, masqueCustom.CustomJA3 = "CUSTOM", "771,4865-4866-4867,0-10-13-16-43-51-57,29-23,0"
	httpsCustom := base
	httpsCustom.Profile, httpsCustom.CustomJA3 = "CUSTOM", "771,4865-4866-4867,0-10-13-16-43-51,29-23,0"
	httpsJump := base
	httpsJump.Type, httpsJump.Host, httpsJump.Port = "HTTPS_JUMP", "gost-h1", 8443
	httpsJump.DialHost = "192.0.2.1" // Only the jump may reach the destination proxy.
	httpsJump.JumpHost, httpsJump.JumpDialHost, httpsJump.JumpPort = "localhost", h2Config.DialHost, h2Config.Port
	httpsJump.JumpUsername, httpsJump.JumpPassword = h2Config.Username, h2Config.Password
	httpsJump.JumpAllowInvalidProxyCertificate = true

	jumpBoth := httpsJump
	jumpBoth.PreferHTTP3, jumpBoth.Host = true, "gost-dual-exit"
	jumpBoth.JumpDialHost, jumpBoth.JumpPort = endpoint(dualJump, "8443")
	jumpOnlyFirst := jumpBoth
	jumpOnlyFirst.Host = "gost-h1"
	jumpOnlyExit := jumpBoth
	jumpOnlyExit.JumpDialHost, jumpOnlyExit.JumpPort = h2Config.DialHost, h2Config.Port
	jumpNeither := httpsJump
	jumpNeither.PreferHTTP3 = true
	_ = endpoint(dualExit, "8443")

	httpsJumpCustom := httpsJump
	httpsJumpCustom.Profile, httpsJumpCustom.CustomJA3 = httpsCustom.Profile, httpsCustom.CustomJA3

	fingerprint := func(container string) string {
		t.Helper()
		key, _, _, _, err := ssh.ParseAuthorizedKey([]byte(dockerTest(t, "exec", container, "cat", "/etc/ssh/ssh_host_ed25519_key.pub")))
		if err != nil {
			t.Fatal(err)
		}
		return ssh.FingerprintSHA256(key)
	}
	sshConfig := config{Type: "SSH", Host: "localhost", Username: "proxy", Password: "ssh-exit-test-password",
		SSHProfile: "OPENSSH", SSHAuthMode: "PASSWORD_ONLY", DoHURL: base.DoHURL}
	sshConfig.DialHost, sshConfig.Port = endpoint(sshExit, "2222")
	sshConfig.TrustedHostKey = fingerprint(sshExit)
	sshKeyConfig := sshConfig
	sshKeyConfig.Password, sshKeyConfig.SSHAuthMode = "", "KEY_ONLY"
	sshKeyConfig.PrivateKey = dockerTest(t, "exec", sshExit, "cat", "/home/proxy/.ssh/id_ed25519")
	sshJumpConfig := sshConfig
	sshJumpConfig.Type, sshJumpConfig.Host, sshJumpConfig.Port = "SSH_JUMP", "ssh-exit", 2222
	sshJumpConfig.DialHost = strings.TrimSpace(dockerTest(t, "inspect", "--format", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", sshExit))
	sshJumpConfig.JumpHost, sshJumpConfig.JumpUsername, sshJumpConfig.JumpPassword = "localhost", "proxy", "ssh-jump-test-password"
	sshJumpConfig.JumpDialHost, sshJumpConfig.JumpPort = endpoint(sshJump, "2222")
	sshJumpConfig.JumpTrustedHostKey = fingerprint(sshJump)

	// An accidental direct connection must not be able to satisfy a positive test.
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	conn, err := (&net.Dialer{}).DialContext(ctx, "tcp", originAddress)
	cancel()
	if err == nil {
		_ = conn.Close()
		t.Fatal("origin unexpectedly reachable directly from test host")
	}
	for _, tc := range []struct {
		name string
		cfg  config
	}{
		{"gost_jump_h3_both", jumpBoth}, {"gost_jump_h3_first_only", jumpOnlyFirst}, {"gost_jump_h3_exit_only", jumpOnlyExit}, {"gost_jump_h3_neither", jumpNeither},
		{"gost_prefer_http3", preferredMasque}, {"gost_prefer_https_fallback", preferredHTTPS}, {"gost_prefer_h2_fallback", preferredH2}, {"gost_masque", masqueConfig}, {"gost_masque_firefox", masqueFirefox}, {"gost_masque_custom", masqueCustom}, {"gost_https", base}, {"gost_https_custom", httpsCustom}, {"gost_https_jump_custom", httpsJumpCustom}, {"gost_http2", h2Config}, {"gost_https_jump", httpsJump},
		{"openssh_password", sshConfig}, {"openssh_key", sshKeyConfig}, {"openssh_jump", sshJumpConfig},
	} {
		t.Run(tc.name, func(t *testing.T) {
			logs := &diagnosticRecorder{}
			dial := realServerDialer(t, tc.cfg, logs)
			transport := &http.Transport{DialContext: func(ctx context.Context, _, address string) (net.Conn, error) {
				return dial(ctx, address)
			}, DisableKeepAlives: true}
			t.Cleanup(transport.CloseIdleConnections)
			client := &http.Client{Transport: transport, Timeout: 15 * time.Second}
			// Fresh HTTP connections exercise reuse of the underlying SSH/H2 session.
			for i := 0; i < 3; i++ {
				payload := make([]byte, 64*1024+i)
				if _, err := rand.Read(payload); err != nil {
					t.Fatal(err)
				}
				response, err := client.Post("http://"+originAddress+"/echo", "application/octet-stream", bytes.NewReader(payload))
				if err != nil {
					t.Fatal(err)
				}
				body, err := io.ReadAll(io.LimitReader(response.Body, int64(len(payload)+1)))
				_ = response.Body.Close()
				if err != nil || response.StatusCode != 200 || response.Header.Get("X-MegaProxy-Origin") != "integration" || !bytes.Equal(body, payload) {
					t.Fatalf("origin round trip failed: status=%d bytes=%d err=%v", response.StatusCode, len(body), err)
				}
			}
			output := logs.text()
			if strings.HasPrefix(tc.name, "gost_jump_h3_") && (strings.Contains(output, "result=fallback") != (tc.name != "gost_jump_h3_both")) {
				t.Fatalf("wrong chain selection: %s", output)
			}
			if tc.cfg.Type == "MASQUE" || tc.name == "gost_prefer_http3" || tc.name == "gost_jump_h3_both" {
				if !strings.Contains(output, "protocol=http3") {
					t.Fatal("missing HTTP/3 negotiation")
				}
			} else if tc.cfg.isHTTPS() {
				if !strings.Contains(output, "tls_version=TLS1.") || !strings.Contains(output, "http_version=HTTP/") {
					t.Fatal("missing HTTPS negotiation")
				}
			} else {
				for _, field := range []string{"kex=", "host_key_algorithm=", "c2s_cipher=", "c2s_mac=", "s2c_cipher=", "s2c_mac="} {
					if !strings.Contains(output, field) {
						t.Fatalf("missing SSH negotiation field %s", field)
					}
				}
				if strings.Contains(output, "=unknown") || strings.Contains(output, "algorithms=unavailable") {
					t.Fatal("real SSH algorithms were not reported")
				}
			}
			for _, secret := range []string{tc.cfg.Password, tc.cfg.JumpPassword, tc.cfg.PrivateKey, tc.cfg.TrustedHostKey, originAddress, "localhost", tc.cfg.DialHost} {
				if secret != "" && strings.Contains(output, secret) {
					t.Fatal("private data in native diagnostics")
				}
			}

		})
	}

	originIP := strings.TrimSpace(dockerTest(t, "inspect", "--format", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", id+"-origin"))
	for _, cfg := range []config{masqueConfig, masqueFirefox, masqueCustom} {
		t.Run("gost_udp_"+cfg.Profile, func(t *testing.T) {
			d := &masqueDialer{config: cfg, protector: &jumpTestProtector{}}
			defer d.Close()
			udp, err := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr(originIP), DstPort: 8081})
			if err != nil {
				t.Fatal(err)
			}
			defer udp.Close()
			target := &net.UDPAddr{IP: net.ParseIP(originIP), Port: 8081}
			// Drop an oversized packet before exercising the same association.
			// Firefox also needs its advertised receive limit to protect echo replies.
			tooLarge := 4096
			if cfg.Profile == "FIREFOX_ANDROID" {
				tooLarge = 1200
			}
			_ = udp.SetWriteDeadline(time.Now().Add(3 * time.Second))
			if _, err := udp.WriteTo(make([]byte, tooLarge), target); err != nil {
				t.Fatal(err)
			}
			sizes := []int{0, 512, 1100}
			if cfg.Profile != "FIREFOX_ANDROID" {
				sizes = append(sizes, 1200)
			}
			for _, size := range sizes {
				_ = udp.SetDeadline(time.Now().Add(3 * time.Second))
				payload := make([]byte, size)
				_, _ = rand.Read(payload)
				if _, err := udp.WriteTo(payload, target); err != nil {
					t.Fatal(err)
				}
				got := make([]byte, size+1)
				n, addr, err := udp.ReadFrom(got)
				if err != nil || addr.String() != target.String() || !bytes.Equal(got[:n], payload) {
					t.Fatalf("UDP round trip size=%d: %v", size, err)
				}
			}
			_ = d.Close()
			if _, err := udp.WriteTo([]byte("closed"), target); err == nil {
				t.Fatal("closed MASQUE UDP stream accepted data")
			}
		})
	}

	for _, tc := range []struct {
		name   string
		cfg    config
		change func(*config)
		want   string
	}{
		{"masque_password", masqueConfig, func(c *config) { c.Password = "wrong" }, "407"},
		{"masque_certificate", masqueConfig, func(c *config) { c.AllowInvalidProxyCertificate = false }, "certificate"},
		{"https_password", base, func(c *config) { c.Password = "wrong" }, "407"},
		{"http2_password", h2Config, func(c *config) { c.Password = "wrong" }, "407"},
		{"https_certificate", base, func(c *config) { c.AllowInvalidProxyCertificate = false }, "certificate"},
		{"https_jump_password", httpsJump, func(c *config) { c.JumpPassword = "wrong" }, "407"},
		{"https_exit_password", httpsJump, func(c *config) { c.Password = "wrong" }, "407"},
		{"https_jump_certificate", httpsJump, func(c *config) { c.JumpAllowInvalidProxyCertificate = false }, "certificate"},
		{"https_exit_certificate", httpsJump, func(c *config) { c.AllowInvalidProxyCertificate = false }, "certificate"},
		{"ssh_password", sshConfig, func(c *config) { c.Password = "wrong" }, "unable to authenticate"},
		{"ssh_host_key", sshConfig, func(c *config) { c.TrustedHostKey = "wrong" }, "SSH_HOST_KEY_CHANGED|destination|"},
		{"ssh_jump_password", sshJumpConfig, func(c *config) { c.JumpPassword = "wrong" }, "unable to authenticate"},
		{"ssh_exit_password", sshJumpConfig, func(c *config) { c.Password = "wrong" }, "unable to authenticate"},
		{"ssh_jump_key", sshJumpConfig, func(c *config) { c.JumpTrustedHostKey = "wrong" }, "SSH_HOST_KEY_CHANGED|jump|"},
		{"ssh_exit_key", sshJumpConfig, func(c *config) { c.TrustedHostKey = "wrong" }, "SSH_HOST_KEY_CHANGED|destination|"},
	} {
		t.Run("reject_"+tc.name, func(t *testing.T) {
			tc.change(&tc.cfg)
			dial := realServerDialer(t, tc.cfg)
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			conn, err := dial(ctx, originAddress)
			if conn != nil {
				_ = conn.Close()
				t.Fatal("rejected credentials/trust still produced a tunnel")
			}
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("expected %q, got %v", tc.want, err)
			}
		})
	}
}

func realServerDialer(t *testing.T, c config, reporters ...Reporter) func(context.Context, string) (net.Conn, error) {
	t.Helper()
	raw, err := json.Marshal(c)
	if err != nil {
		t.Fatal(err)
	}
	c, err = parseConfig(string(raw))
	if err != nil {
		t.Fatal(err)
	}
	protector := &jumpTestProtector{}
	var reporter Reporter
	if len(reporters) > 0 {
		reporter = reporters[0]
	}
	preferred, err := preferredHTTP3(context.Background(), c, protector, reporter, nil)
	if err != nil {
		t.Fatal(err)
	}
	if preferred != nil {
		t.Cleanup(func() { _ = preferred.Close() })
		return preferred.connectTarget
	}
	if c.isHTTPS() {
		d := &httpsConnectDialer{config: c, protector: protector, reporter: reporter}
		t.Cleanup(func() { _ = d.Close() })
		return d.connectTarget
	}
	if c.Type == "MASQUE" {
		d := &masqueDialer{config: c, protector: protector, reporter: reporter}
		t.Cleanup(func() { _ = d.Close() })
		return d.connectTarget
	}
	d := &sshDialer{config: c, protector: protector, reporter: reporter}
	t.Cleanup(func() { _ = d.Close() })
	return d.connectTarget
}

func dockerTest(t *testing.T, args ...string) string {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	output, err := exec.CommandContext(ctx, "docker", args...).CombinedOutput()
	if err != nil {
		t.Fatalf("docker %s: %v\n%s", strings.Join(args, " "), err, output)
	}
	return string(output)
}
