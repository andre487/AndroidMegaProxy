package mobile

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"sync"
	"time"

	quic "github.com/refraction-networking/uquic"
	"github.com/refraction-networking/uquic/http3"
	tls "github.com/refraction-networking/utls"
)

type http3ProbeResult struct {
	Provider string `json:"provider"`
	Status   string `json:"status"`
}

var http3TestEndpoints = []testEndpoint{
	{host: "www.cloudflare.com", path: "/cdn-cgi/trace"},
	{host: "quic.browserleaks.com", path: "/"},
}

func checkHTTP3Providers(ctx context.Context, reporter Reporter, fetch func(context.Context, testEndpoint) error) []http3ProbeResult {
	results := make([]http3ProbeResult, len(http3TestEndpoints))
	var workers sync.WaitGroup
	for index, endpoint := range http3TestEndpoints {
		workers.Go(func() {
			attemptCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			defer cancel()
			status, reason := "confirmed", "none"
			if err := fetch(attemptCtx, endpoint); err != nil {
				status, reason = "unavailable", errorClass(err)
			}
			results[index] = http3ProbeResult{Provider: endpoint.host, Status: status}
			report(reporter, "event=connection_test stage=inner_http3_provider provider_index=%d result=%s reason=%s", index, status, reason)
		})
	}
	workers.Wait()
	return results
}

func testHTTP3Get(ctx context.Context, d *masqueDialer, endpoint testEndpoint) error {
	stream, err := d.openTunnel(ctx, net.JoinHostPort(endpoint.host, "443"), true)
	if err != nil {
		return err
	}
	// The hostname is resolved by GOST. This address only identifies the virtual
	// packet peer inside QUIC; no DNS or direct UDP socket is opened here.
	peer := &net.UDPAddr{IP: net.IPv4(192, 0, 2, 1), Port: 443}
	packet := d.udpPacketConn(stream, peer)
	return testHTTP3Exchange(ctx, packet, peer, endpoint, nil)
}

func testHTTP3Exchange(ctx context.Context, packet net.PacketConn, peer net.Addr, endpoint testEndpoint, tlsConfig *tls.Config) error {
	defer packet.Close()
	stop := context.AfterFunc(ctx, func() { _ = packet.Close() })
	defer stop()
	transport := &quic.Transport{Conn: packet}
	defer transport.Close()
	client := &http3.Transport{
		TLSClientConfig:        tlsConfig,
		DisableCompression:     true,
		MaxResponseHeaderBytes: 16 * 1024,
		QUICConfig:             &quic.Config{InitialPacketSize: 1200, DisablePathMTUDiscovery: true, HandshakeIdleTimeout: 5 * time.Second, MaxIdleTimeout: 10 * time.Second},
		Dial: func(ctx context.Context, _ string, config *tls.Config, options *quic.Config) (*quic.Conn, error) {
			return transport.Dial(ctx, peer, config, options)
		},
	}
	defer client.Close()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, "https://"+endpoint.host+endpoint.path, nil)
	if err != nil {
		return err
	}
	request.Header.Set("User-Agent", "MegaProxy/0.1 connection-test")
	response, err := client.RoundTrip(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.ProtoMajor != 3 {
		return errors.New("HTTP/3 was not negotiated")
	}
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP/3 server returned status %d", response.StatusCode)
	}
	// Complete a bounded response, rather than count a handshake as success.
	_, err = io.Copy(io.Discard, io.LimitReader(response.Body, 64*1024))
	return err
}
