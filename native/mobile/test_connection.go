package mobile

import (
	"bufio"
	"context"
	stdtls "crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

type testEndpoint struct {
	host  string
	path  string
	parse func(string) (string, bool)
}

var publicIPEndpoints = []testEndpoint{
	{host: "ifconfig.me", path: "/ip", parse: parseIPAddress},
	{host: "api.ipify.org", path: "/", parse: parseIPAddress},
	{host: "icanhazip.com", path: "/", parse: parseIPAddress},
}

var countryEndpoints = []testEndpoint{
	{host: "ifconfig.co", path: "/country-iso", parse: parseCountryCode},
	{host: "ipapi.co", path: "/country_code/", parse: parseCountryCode},
	{host: "api.country.is", path: "/", parse: parseCountryJSON},
}

type connectionTestResult struct {
	ExitIP      string `json:"exitIp"`
	CountryCode string `json:"countryCode,omitempty"`
}

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

	ip, err := lookupTestValue(ctx, connect, testReporter, "exit_ip", publicIPEndpoints)
	if err != nil {
		return "", fmt.Errorf("public IP check: %w", err)
	}
	report(reporter, "event=connection_test stage=exit_ip result=success family=%s", ipFamily(ip))

	countryCode, countryErr := lookupTestValue(ctx, connect, testReporter, "exit_country", countryEndpoints)
	if countryErr != nil {
		report(reporter, "event=connection_test stage=exit_country result=unavailable")
	} else {
		report(reporter, "event=connection_test stage=exit_country result=success country=%s", countryCode)
	}
	encoded, err := json.Marshal(connectionTestResult{ExitIP: ip, CountryCode: countryCode})
	if err != nil {
		return "", fmt.Errorf("encode connection test result: %w", err)
	}
	return string(encoded), nil
}

func lookupTestValue(ctx context.Context, connect func(context.Context, string) (net.Conn, error), reporter Reporter, stage string, endpoints []testEndpoint) (string, error) {
	return lookupEndpointValue(ctx, reporter, stage, endpoints, func(attemptCtx context.Context, endpoint testEndpoint) (string, error) {
		return testHTTPSGet(attemptCtx, connect, reporter, endpoint.host, endpoint.path, true)
	})
}

func lookupEndpointValue(ctx context.Context, reporter Reporter, stage string, endpoints []testEndpoint, fetch func(context.Context, testEndpoint) (string, error)) (string, error) {
	var lastErr error
	for index, endpoint := range endpoints {
		attemptCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		body, err := fetch(attemptCtx, endpoint)
		cancel()
		if err == nil {
			if value, valid := endpoint.parse(body); valid {
				report(reporter, "event=connection_test stage=%s_provider result=success attempt=%d provider=%s", stage, index+1, endpoint.host)
				return value, nil
			}
			err = fmt.Errorf("invalid response")
		}
		lastErr = err
		report(reporter, "event=connection_test stage=%s_provider result=failed attempt=%d provider=%s", stage, index+1, endpoint.host)
	}
	return "", fmt.Errorf("all %d providers failed: %w", len(endpoints), lastErr)
}

func parseIPAddress(body string) (string, bool) {
	value := strings.TrimSpace(body)
	return value, len(value) <= 128 && net.ParseIP(value) != nil
}

func parseCountryCode(body string) (string, bool) {
	value := strings.ToUpper(strings.TrimSpace(body))
	return value, len(value) == 2 && value[0] >= 'A' && value[0] <= 'Z' && value[1] >= 'A' && value[1] <= 'Z'
}

func parseCountryJSON(body string) (string, bool) {
	var response struct {
		Country string `json:"country"`
	}
	if err := json.Unmarshal([]byte(body), &response); err != nil {
		return "", false
	}
	return parseCountryCode(response.Country)
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
	tlsState := connection.ConnectionState()
	report(reporter, "event=connection_test stage=destination_tls result=success certificate=verified version=0x%04x cipher=0x%04x alpn=%s h2_negotiated=%t session_resumed=%t", tlsState.Version, tlsState.CipherSuite, normalizedALPN(tlsState.NegotiatedProtocol), tlsState.NegotiatedProtocol == "h2", tlsState.DidResume)

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
