package mobile

import (
	"context"
	"crypto/x509"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	quic "github.com/refraction-networking/uquic"
	"github.com/refraction-networking/uquic/http3"
	tls "github.com/refraction-networking/utls"
)

func TestHTTP3ProvidersRemainIndependent(t *testing.T) {
	results := checkHTTP3Providers(context.Background(), nil, func(ctx context.Context, endpoint testEndpoint) error {
		if _, ok := ctx.Deadline(); !ok {
			t.Error("missing provider deadline")
		}
		if endpoint.host == "www.cloudflare.com" {
			return errors.New("offline")
		}
		return nil
	})
	if len(results) != 2 || results[0].Status != "unavailable" || results[1].Status != "confirmed" {
		t.Fatalf("results: %+v", results)
	}
}

func TestInnerHTTP3ThroughMASQUEDatagrams(t *testing.T) {
	for _, jump := range []bool{false, true} {
		for _, size := range []uint16{1200, 1280, 1350} {
			t.Run(fmt.Sprintf("jump=%t/origin_packet=%d", jump, size), func(t *testing.T) { testInnerHTTP3(t, jump, size) })
		}
	}
}

func testInnerHTTP3(t *testing.T, jump bool, originPacketSize uint16) {
	var requests atomic.Int32
	origin := newMasqueFixture(t, nil, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.ProtoMajor != 3 {
			t.Error("origin did not receive HTTP/3")
		}
		requests.Add(1)
		_, _ = w.Write([]byte("http=h3\n"))
	}))
	// Exercise genuine site QUIC flights, including packets larger than 1200.
	originPacket, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	originTransport := &quic.Transport{Conn: originPacket}
	originListener, err := originTransport.Listen(http3.ConfigureTLSConfig(origin.server.TLSConfig), &quic.Config{InitialPacketSize: originPacketSize, DisablePathMTUDiscovery: true})
	if err != nil {
		t.Fatal(err)
	}
	go func() { _ = origin.server.ServeListener(originListener) }()
	defer originPacket.Close()
	defer originTransport.Close()
	defer originListener.Close()
	roots := x509.NewCertPool()
	certificate, err := x509.ParseCertificate(origin.server.TLSConfig.Certificates[0].Certificate[0])
	if err != nil {
		t.Fatal(err)
	}
	roots.AddCert(certificate)
	proxy := newMasqueFixture(t, nil, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Proto != "connect-udp" {
			w.WriteHeader(400)
			return
		}
		udp, err := net.Dial("udp", originListener.Addr().String())
		if err != nil {
			t.Error(err)
			w.WriteHeader(502)
			return
		}
		defer udp.Close()
		w.Header().Set("Capsule-Protocol", "?1")
		w.WriteHeader(200)
		stream := w.(http3.HTTPStreamer).HTTPStream()
		defer stream.Close()
		stop := context.AfterFunc(r.Context(), func() { _ = udp.Close() })
		defer stop()
		go func() {
			buffer := make([]byte, 65536)
			for {
				n, err := udp.Read(buffer[1:])
				if err != nil {
					return
				}
				buffer[0] = 0 // CONNECT-UDP context ID.
				if stream.SendDatagram(buffer[:n+1]) != nil {
					return
				}
			}
		}()
		for {
			data, err := stream.ReceiveDatagram(r.Context())
			if err != nil {
				return
			}
			if len(data) > 1 && data[0] == 0 {
				if _, err := udp.Write(data[1:]); err != nil {
					return
				}
			}
		}
	}))
	d := &masqueDialer{config: proxy.config, protector: &jumpTestProtector{}}
	if jump {
		var err error
		d, err = preferredHTTP3(context.Background(), http3JumpFixture(t, proxy.config), &jumpTestProtector{}, nil, nil)
		if err != nil || d == nil {
			t.Fatalf("nested selection: %v", err)
		}
	} else {
		d.probePeerMTU = true
		if _, err := d.getSession(context.Background()); err != nil {
			t.Fatal(err)
		}
	}
	defer d.Close()
	endpoint := testEndpoint{host: "example.com", path: "/"}
	for _, trusted := range []bool{true, false} {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		stream, err := d.openTunnel(ctx, "example.com:443", true)
		if err != nil {
			cancel()
			t.Fatal(err)
		}
		peer := &net.UDPAddr{IP: net.IPv4(192, 0, 2, 1), Port: 443}
		config := &tls.Config{}
		if trusted {
			config.RootCAs = roots
		}
		err = testHTTP3Exchange(ctx, d.udpPacketConn(stream, peer), peer, endpoint, config)
		cancel()
		if trusted && err != nil {
			t.Fatal(err)
		}
		if !trusted && err == nil {
			t.Fatal("untrusted origin certificate accepted")
		}
	}
	if requests.Load() != 1 {
		t.Fatalf("origin requests: %d", requests.Load())
	}

	ctx, cancel := context.WithTimeout(context.Background(), 80*time.Millisecond)
	defer cancel()
	packet, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	blackhole, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer blackhole.Close()
	started := time.Now()
	err = testHTTP3Exchange(ctx, packet, blackhole.LocalAddr(), endpoint, nil)
	if err == nil || time.Since(started) > time.Second || !strings.Contains(err.Error(), "context") {
		t.Fatalf("UDP timeout: %v", err)
	}
}
