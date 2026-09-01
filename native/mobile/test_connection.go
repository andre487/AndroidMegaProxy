package mobile

import (
	"bufio"
	"context"
	stdtls "crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

// TestConnection verifies the configured proxy path without starting a TUN device.
func TestConnection(rawConfig string, protector Protector, reporter Reporter) (string, error) {
	c, err := parseConfig(rawConfig)
	if err != nil {
		return "", err
	}
	var connect func(context.Context, string) (net.Conn, error)
	var testReporter Reporter
	if c.Type == "HTTPS" {
		dialer := &httpsConnectDialer{config: c, protector: protector, reporter: reporter}
		connect, testReporter = dialer.connectTarget, dialer.reporter
	} else {
		dialer := &sshDialer{config: c, protector: protector, reporter: reporter}
		connect, testReporter = dialer.connectTarget, dialer.reporter
		defer dialer.invalidate()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()

	report(reporter, "event=connection_test result=started")
	if _, err := testHTTPSGet(ctx, connect, testReporter, "example.com", "/", false); err != nil {
		return "", fmt.Errorf("example.com check: %w", err)
	}
	report(reporter, "event=connection_test stage=https_check result=success")

	ip, err := testHTTPSGet(ctx, connect, testReporter, "ifconfig.me", "/ip", true)
	if err != nil {
		return "", fmt.Errorf("public IP check: %w", err)
	}
	ip = strings.TrimSpace(ip)
	if ip == "" || len(ip) > 128 {
		return "", fmt.Errorf("public IP check returned an invalid response")
	}
	report(reporter, "event=connection_test stage=exit_ip result=success family=%s", ipFamily(ip))
	return ip, nil
}

func ipFamily(value string) string {
	ip := net.ParseIP(strings.TrimSpace(value))
	if ip == nil {
		return "unknown"
	}
	if ip.To4() != nil {
		return "ipv4"
	}
	return "ipv6"
}

func testHTTPSGet(ctx context.Context, connect func(context.Context, string) (net.Conn, error), reporter Reporter, host, path string, readBody bool) (string, error) {
	tunnel, err := connect(ctx, host+":443")
	if err != nil {
		return "", err
	}
	defer tunnel.Close()

	connection := stdtls.Client(tunnel, &stdtls.Config{ServerName: host, MinVersion: stdtls.VersionTLS12})
	if err := connection.HandshakeContext(ctx); err != nil {
		return "", fmt.Errorf("destination TLS handshake: %w", err)
	}
	report(reporter, "event=connection_test stage=destination_tls result=success certificate=verified")

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://"+host+path, nil)
	if err != nil {
		return "", err
	}
	request.Header.Set("User-Agent", "MegaProxy/0.1 connection-test")
	request.Header.Set("Connection", "close")
	if err := request.Write(connection); err != nil {
		return "", fmt.Errorf("write HTTPS request: %w", err)
	}
	response, err := http.ReadResponse(bufio.NewReader(connection), request)
	if err != nil {
		return "", fmt.Errorf("read HTTPS response: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 400 {
		return "", fmt.Errorf("HTTPS server returned %s", response.Status)
	}
	if !readBody {
		return "", nil
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, 256))
	return string(body), err
}
