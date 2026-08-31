package mobile

import (
	"bufio"
	"context"
	stdtls "crypto/tls"
	"fmt"
	"io"
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
	dialer := &httpsConnectDialer{config: c, protector: protector, reporter: reporter}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()

	report(reporter, "Connection test started")
	if _, err := testHTTPSGet(ctx, dialer, "example.com", "/", false); err != nil {
		return "", fmt.Errorf("example.com check: %w", err)
	}
	report(reporter, "example.com HTTPS check succeeded")

	ip, err := testHTTPSGet(ctx, dialer, "ifconfig.me", "/ip", true)
	if err != nil {
		return "", fmt.Errorf("public IP check: %w", err)
	}
	ip = strings.TrimSpace(ip)
	if ip == "" || len(ip) > 128 {
		return "", fmt.Errorf("public IP check returned an invalid response")
	}
	report(reporter, "Connection test succeeded; proxy exit IP=%s", ip)
	return ip, nil
}

func testHTTPSGet(ctx context.Context, dialer *httpsConnectDialer, host, path string, readBody bool) (string, error) {
	tunnel, err := dialer.connectTarget(ctx, host+":443")
	if err != nil {
		return "", err
	}
	defer tunnel.Close()

	connection := stdtls.Client(tunnel, &stdtls.Config{ServerName: host, MinVersion: stdtls.VersionTLS12})
	if err := connection.HandshakeContext(ctx); err != nil {
		return "", fmt.Errorf("destination TLS handshake: %w", err)
	}
	report(dialer.reporter, "%s: destination TLS certificate verified", host)

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
