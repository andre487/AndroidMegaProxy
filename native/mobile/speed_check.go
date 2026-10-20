package mobile

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	quic "github.com/refraction-networking/uquic"
	"github.com/refraction-networking/uquic/http3"
	utls "github.com/refraction-networking/utls"
	"golang.org/x/net/http2"
)

// SpeedNetwork resolves names and binds every TCP/UDP socket to one Android Network.
// Bind receives a borrowed descriptor; implementations must not close it.
type SpeedNetwork interface {
	Bind(fd int) bool
	Resolve(host string) (string, error)
}

type SpeedTest struct {
	ctx     context.Context
	cancel  context.CancelFunc
	started atomic.Bool
}

func NewSpeedTest() *SpeedTest {
	ctx, cancel := context.WithCancel(context.Background())
	return &SpeedTest{ctx: ctx, cancel: cancel}
}
func (s *SpeedTest) Cancel() { s.cancel() }

type speedResult struct {
	ExitIP       string   `json:"exitIp,omitempty"`
	CountryCode  string   `json:"countryCode,omitempty"`
	Protocol     string   `json:"protocol,omitempty"`
	LatencyMS    *int64   `json:"latencyMs,omitempty"`
	DownloadMbps *float64 `json:"downloadMbps,omitempty"`
	UploadMbps   *float64 `json:"uploadMbps,omitempty"`
}

var errSpeedALPN = errors.New("requested HTTP protocol was not negotiated")
var errSpeedBind = errors.New("speed socket bind rejected")

func speedProtocols(preferred string) []string {
	switch preferred {
	case "h3":
		return []string{"h3", "h2", "h1"}
	case "h2":
		return []string{"h2", "h1"}
	case "h1":
		return []string{"h1"}
	default:
		return nil
	}
}

// Run streams partial result JSON separately from privacy-safe diagnostic events.
// Fallback is limited to protocol/network failures during the initial metadata probe.
func (s *SpeedTest) Run(network SpeedNetwork, preferred string, megabytes int, updates Reporter, logs Reporter) error {
	if !s.started.CompareAndSwap(false, true) {
		return errors.New("speed test already started")
	}
	defer s.cancel()
	if network == nil || (megabytes != 10 && megabytes != 25 && megabytes != 50) || speedProtocols(preferred) == nil {
		return errors.New("invalid speed test parameters")
	}
	ctx, cancel := context.WithTimeout(s.ctx, 5*time.Minute)
	defer cancel()
	return runSpeedTest(ctx, preferred, int64(megabytes)*1_000_000, updates, logs, "https://speed.cloudflare.com", func(protocol string) (http.RoundTripper, func()) { return speedTransport(network, protocol, logs) })
}

func runSpeedTest(ctx context.Context, preferred string, bytes int64, updates Reporter, logs Reporter, origin string, newTransport func(string) (http.RoundTripper, func())) error {
	var client *http.Client
	var result speedResult
	for index, protocol := range speedProtocols(preferred) {
		transport, closeTransport := newTransport(protocol)
		candidate := &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
		probeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
		response, _, err := speedRequest(probeCtx, candidate, protocol, "metadata", origin+"/cdn-cgi/trace", 0, logs)
		if err == nil {
			body, readErr := io.ReadAll(io.LimitReader(response.Body, 16*1024))
			response.Body.Close()
			err = readErr
			if err == nil {
				trace := map[string]string{}
				for _, line := range strings.Split(string(body), "\n") {
					if key, value, ok := strings.Cut(line, "="); ok {
						trace[key] = value
					}
				}
				if net.ParseIP(trace["ip"]) == nil {
					err = errors.New("invalid speed metadata")
				} else {
					result.ExitIP, result.CountryCode = trace["ip"], trace["loc"]
					result.Protocol = response.Proto
					if response.TLS != nil {
						result.Protocol += " · " + tls.VersionName(response.TLS.Version)
					} else if trace["tls"] != "" {
						result.Protocol += " · " + trace["tls"]
					}
				}
			}
		}
		cancel()
		if err == nil {
			client = candidate
			defer closeTransport()
			report(logs, "event=speed_test stage=protocol result=selected protocol=%s", protocol)
			break
		}
		closeTransport()
		report(logs, "event=speed_test stage=protocol result=failed protocol=%s reason=%s", protocol, speedErrorClass(err))
		if index == len(speedProtocols(preferred))-1 || !speedCanFallback(err) {
			return err
		}
		report(logs, "event=speed_test stage=protocol result=fallback from=%s to=%s", protocol, speedProtocols(preferred)[index+1])
	}
	publishSpeed(updates, result)
	// Reuse only the successfully negotiated protocol for all measurements.
	protocol := strings.ToLower(strings.Split(result.Protocol, " · ")[0])
	var latencies []int64
	for sample := 0; sample < 3; sample++ {
		response, elapsed, err := speedRequest(ctx, client, protocol, "latency", origin+"/__down?bytes=0", 0, logs)
		if err != nil {
			return err
		}
		_, err = io.Copy(io.Discard, io.LimitReader(response.Body, 1024))
		response.Body.Close()
		if err != nil {
			return err
		}
		latencies = append(latencies, elapsed.Milliseconds())
		report(logs, "event=speed_test stage=latency sample=%d ttfb_ms=%d", sample+1, elapsed.Milliseconds())
	}
	sort.Slice(latencies, func(i, j int) bool { return latencies[i] < latencies[j] })
	result.LatencyMS = &latencies[1]
	publishSpeed(updates, result)
	download, err := speedTransfer(ctx, client, protocol, origin, bytes, false, logs)
	if err != nil {
		return err
	}
	result.DownloadMbps = &download
	publishSpeed(updates, result)
	upload, err := speedTransfer(ctx, client, protocol, origin, bytes, true, logs)
	if err != nil {
		return err
	}
	result.UploadMbps = &upload
	publishSpeed(updates, result)
	return nil
}

func publishSpeed(updates Reporter, result speedResult) {
	data, _ := json.Marshal(result)
	report(updates, "%s", data)
}

func speedErrorClass(err error) string {
	if errors.Is(err, errSpeedALPN) {
		return "alpn_mismatch"
	}
	if errors.Is(err, errSpeedBind) {
		return "network_bind"
	}
	return errorClass(err)
}

func speedCanFallback(err error) bool {
	if errors.Is(err, context.Canceled) || errors.Is(err, errSpeedBind) {
		return false
	}
	var cert *tls.CertificateVerificationError
	var unknown x509.UnknownAuthorityError
	var invalid x509.CertificateInvalidError
	var hostname x509.HostnameError
	if errors.As(err, &cert) || errors.As(err, &unknown) || errors.As(err, &invalid) || errors.As(err, &hostname) || speedErrorClass(err) == "certificate" {
		return false
	}
	if errors.Is(err, errSpeedALPN) {
		return true
	}
	var requestError *url.Error
	if errors.As(err, &requestError) {
		return speedCanFallback(requestError.Err)
	}
	var netErr net.Error
	return errors.As(err, &netErr) || errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF)
}

type speedHTTPError int

func (e speedHTTPError) Error() string { return fmt.Sprintf("speed service HTTP %d", int(e)) }

func speedRequest(ctx context.Context, client *http.Client, protocol, stage, url string, bytes int64, logs Reporter) (*http.Response, time.Duration, error) {
	method := http.MethodGet
	var body io.Reader
	if stage == "upload" {
		method, body = http.MethodPost, io.LimitReader(speedZeroReader{}, bytes)
	}
	req, err := http.NewRequestWithContext(ctx, method, url, body)
	if err != nil {
		return nil, 0, err
	}
	if body != nil {
		req.ContentLength = bytes
		req.Header.Set("Content-Type", "application/octet-stream")
	}
	req.Header.Set("Accept-Encoding", "identity")
	req.Header.Set("User-Agent", "MegaProxy speed-test")
	started := time.Now()
	var tlsStarted time.Time
	trace := &httptrace.ClientTrace{
		TLSHandshakeStart: func() { tlsStarted = time.Now() },
		TLSHandshakeDone: func(state tls.ConnectionState, err error) {
			report(logs, "event=speed_test stage=tls elapsed_ms=%d version=%s alpn=%s reason=%s", time.Since(tlsStarted).Milliseconds(), tls.VersionName(state.Version), state.NegotiatedProtocol, speedErrorClass(err))
		},
		GotConn: func(info httptrace.GotConnInfo) {
			report(logs, "event=speed_test stage=connection reused=%t idle=%t", info.Reused, info.WasIdle)
		},
	}
	report(logs, "event=speed_test stage=%s result=started protocol=%s bytes=%d", stage, protocol, bytes)
	response, err := client.Do(req.WithContext(httptrace.WithClientTrace(ctx, trace)))
	elapsed := time.Since(started)
	if err != nil {
		report(logs, "event=speed_test stage=%s result=failed elapsed_ms=%d reason=%s", stage, elapsed.Milliseconds(), speedErrorClass(err))
		return nil, elapsed, err
	}
	report(logs, "event=speed_test stage=%s result=headers status=%d protocol=%s ttfb_ms=%d", stage, response.StatusCode, response.Proto, elapsed.Milliseconds())
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		response.Body.Close()
		return nil, elapsed, speedHTTPError(response.StatusCode)
	}
	return response, elapsed, nil
}

type speedZeroReader struct{}

func (speedZeroReader) Read(p []byte) (int, error) { clear(p); return len(p), nil }

func speedTransfer(ctx context.Context, client *http.Client, protocol, origin string, bytes int64, upload bool, logs Reporter) (float64, error) {
	stage, url, bodyBytes := "download", origin+"/__down?bytes="+strconv.FormatInt(bytes, 10), int64(0)
	if upload {
		stage, url, bodyBytes = "upload", origin+"/__up", bytes
	}
	started := time.Now()
	response, _, err := speedRequest(ctx, client, protocol, stage, url, bodyBytes, logs)
	if err != nil {
		return 0, err
	}
	defer response.Body.Close()
	limit := bytes + 1
	if upload {
		limit = 64 * 1024
	}
	received, err := io.Copy(io.Discard, io.LimitReader(response.Body, limit))
	if err == nil && !upload && received != bytes {
		err = io.ErrUnexpectedEOF
	}
	if err != nil {
		report(logs, "event=speed_test stage=%s result=failed bytes=%d reason=%s", stage, received, speedErrorClass(err))
		return 0, err
	}
	elapsed := time.Since(started)
	rate := float64(bytes) * 8 / elapsed.Seconds() / 1_000_000
	report(logs, "event=speed_test stage=%s result=success bytes=%d elapsed_ms=%d mbps=%.3f", stage, bytes, elapsed.Milliseconds(), rate)
	return rate, nil
}

func speedTransport(network SpeedNetwork, protocol string, logs Reporter) (http.RoundTripper, func()) {
	control := func(_, _ string, raw syscall.RawConn) error {
		var bindErr error
		if err := raw.Control(func(fd uintptr) {
			if !network.Bind(int(fd)) {
				bindErr = errSpeedBind
			}
		}); err != nil {
			return err
		}
		return bindErr
	}
	resolve := func(host string) ([]net.IP, error) {
		started := time.Now()
		addresses, err := network.Resolve(host)
		var ips []net.IP
		for _, address := range strings.Fields(addresses) {
			if ip := net.ParseIP(address); ip != nil {
				ips = append(ips, ip)
			}
		}
		if err == nil && len(ips) == 0 {
			err = &net.DNSError{Err: "no addresses", Name: host}
		}
		report(logs, "event=speed_test stage=dns addresses=%d elapsed_ms=%d reason=%s", len(ips), time.Since(started).Milliseconds(), speedErrorClass(err))
		return ips, err
	}
	dial := func(ctx context.Context, kind, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil {
			return nil, err
		}
		ips, err := resolve(host)
		if err != nil {
			return nil, err
		}
		var last error
		for _, ip := range ips {
			d := net.Dialer{Control: control, Timeout: 10 * time.Second}
			started := time.Now()
			conn, err := d.DialContext(ctx, kind, net.JoinHostPort(ip.String(), port))
			report(logs, "event=speed_test stage=tcp elapsed_ms=%d reason=%s", time.Since(started).Milliseconds(), speedErrorClass(err))
			if err == nil {
				return conn, nil
			}
			last = err
		}
		return nil, last
	}
	if protocol == "h3" {
		transport := &http3.Transport{DisableCompression: true, QUICConfig: &quic.Config{InitialPacketSize: 1200, DisablePathMTUDiscovery: true, HandshakeIdleTimeout: 5 * time.Second, MaxIdleTimeout: 30 * time.Second}}
		transport.Dial = func(ctx context.Context, address string, config *utls.Config, options *quic.Config) (*quic.Conn, error) {
			host, port, err := net.SplitHostPort(address)
			if err != nil {
				return nil, err
			}
			ips, err := resolve(host)
			if err != nil {
				return nil, err
			}
			portNumber, _ := strconv.Atoi(port)
			var last error
			for _, ip := range ips {
				kind, local := "udp4", "0.0.0.0:0"
				if ip.To4() == nil {
					kind, local = "udp6", "[::]:0"
				}
				lc := net.ListenConfig{Control: control}
				packet, err := lc.ListenPacket(ctx, kind, local)
				if err != nil {
					return nil, err
				}
				qt := &quic.Transport{Conn: packet}
				started := time.Now()
				conn, err := qt.Dial(ctx, &net.UDPAddr{IP: ip, Port: portNumber}, config, options)
				report(logs, "event=speed_test stage=quic elapsed_ms=%d reason=%s %s", time.Since(started).Milliseconds(), speedErrorClass(err), quicErrorDetails(err))
				if err == nil {
					go func() { <-conn.Context().Done(); qt.Close(); packet.Close() }()
					return conn, nil
				}
				qt.Close()
				packet.Close()
				last = err
				if !speedCanFallback(err) {
					return nil, err
				}
			}
			return nil, last
		}
		return transport, func() { transport.Close() }
	}
	if protocol == "h2" {
		transport := &http2.Transport{DisableCompression: true, DialTLSContext: func(ctx context.Context, kind, address string, config *tls.Config) (net.Conn, error) {
			raw, err := dial(ctx, kind, address)
			if err != nil {
				return nil, err
			}
			conn := tls.Client(raw, config)
			started := time.Now()
			err = conn.HandshakeContext(ctx)
			state := conn.ConnectionState()
			report(logs, "event=speed_test stage=tls elapsed_ms=%d version=%s alpn=%s reason=%s", time.Since(started).Milliseconds(), tls.VersionName(state.Version), state.NegotiatedProtocol, speedErrorClass(err))
			if err != nil {
				raw.Close()
				return nil, err
			}
			if conn.ConnectionState().NegotiatedProtocol != "h2" {
				conn.Close()
				return nil, errSpeedALPN
			}
			return conn, nil
		}}
		return transport, transport.CloseIdleConnections
	}
	transport := &http.Transport{DialContext: dial, DisableCompression: true, TLSClientConfig: &tls.Config{NextProtos: []string{"http/1.1"}}, TLSNextProto: map[string]func(string, *tls.Conn) http.RoundTripper{}}
	return transport, transport.CloseIdleConnections
}
