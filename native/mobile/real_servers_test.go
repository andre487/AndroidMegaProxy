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
	"os/exec"
	"strconv"
	"strings"
	"testing"
	"time"

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
		if port != "" {
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
	httpsJump := base
	httpsJump.Type, httpsJump.Host, httpsJump.Port = "HTTPS_JUMP", "gost-h1", 8443
	httpsJump.DialHost = "192.0.2.1" // Only the jump may reach the destination proxy.
	httpsJump.JumpHost, httpsJump.JumpDialHost, httpsJump.JumpPort = "localhost", h2Config.DialHost, h2Config.Port
	httpsJump.JumpUsername, httpsJump.JumpPassword = h2Config.Username, h2Config.Password
	httpsJump.JumpAllowInvalidProxyCertificate = true

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
		{"gost_https", base}, {"gost_http2", h2Config}, {"gost_https_jump", httpsJump},
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
			if tc.cfg.isHTTPS() {
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

	for _, tc := range []struct {
		name   string
		cfg    config
		change func(*config)
		want   string
	}{
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
	if c.isHTTPS() {
		d := &httpsConnectDialer{config: c, protector: protector, reporter: reporter}
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
