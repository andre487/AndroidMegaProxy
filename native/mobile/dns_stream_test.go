package mobile

import (
	"context"
	"encoding/binary"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"
)

func TestTCPDNSUsesDoHFallbackAndIPv6Policy(t *testing.T) {
	for _, kind := range []string{"HTTPS", "SSH", "MASQUE", "SOCKS5"} {
		t.Run(kind, func(t *testing.T) {
			var requests atomic.Int32
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				requests.Add(1)
				if r.URL.Path == "/primary" {
					w.WriteHeader(503)
					return
				}
				query, err := io.ReadAll(r.Body)
				if err != nil {
					return
				}
				query[2] |= 0x80
				w.Header().Set("Content-Type", "application/dns-message")
				_, _ = w.Write(query)
			}))
			defer server.Close()
			cfg := config{DoHURL: server.URL + "/primary", DoHFallbackURLs: []string{server.URL + "/fallback"}, BypassLocalNetworks: true}
			var dial func(context.Context, *M.Metadata) (net.Conn, error)
			var closeDialer func() error
			client, limiter := server.Client(), make(chan struct{}, 8)
			switch kind {
			case "HTTPS":
				d := &httpsConnectDialer{config: cfg, dohClient: client, dohInFlight: limiter}
				dial, closeDialer = d.DialContext, d.Close
			case "SSH":
				d := &sshDialer{config: cfg, dohClient: client, dohInFlight: limiter}
				dial, closeDialer = d.DialContext, d.Close
			case "MASQUE":
				d := &masqueDialer{config: cfg, dohClient: client, dohInFlight: limiter}
				dial, closeDialer = d.DialContext, d.Close
			default:
				d := &socks5Dialer{config: cfg, dohClient: client, dohInFlight: limiter}
				dial, closeDialer = d.DialContext, d.Close
			}
			defer closeDialer()
			metadata := &M.Metadata{DstIP: netip.MustParseAddr("10.77.0.2"), DstPort: 53}
			conn, err := dial(context.Background(), metadata)
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
			query, _, _ := buildAQuery("example.test")
			written := make(chan error, 1)
			go func() {
				for id := uint16(1); id <= 2; id++ {
					message := append([]byte{0, byte(len(query))}, query...)
					binary.BigEndian.PutUint16(message[2:4], id)
					for _, part := range [][]byte{message[:1], message[1:3], message[3:]} {
						if _, err := conn.Write(part); err != nil {
							written <- err
							return
						}
					}
				}
				written <- nil
			}()
			ids := map[uint16]bool{}
			for i := 0; i < 2; i++ {
				reply := readDNSFrame(t, conn)
				ids[binary.BigEndian.Uint16(reply[:2])] = true
				if reply[2]&0x80 == 0 {
					t.Fatal("not a DNS response")
				}
			}
			if err := <-written; err != nil {
				t.Fatal(err)
			}
			if !ids[1] || !ids[2] || requests.Load() < 3 {
				t.Fatalf("missing fallback/pipelined replies: %v, requests=%d", ids, requests.Load())
			}
			before := requests.Load()
			binary.BigEndian.PutUint16(query[len(query)-4:len(query)-2], 28)
			if _, err := conn.Write(append([]byte{0, byte(len(query))}, query...)); err != nil {
				t.Fatal(err)
			}
			if reply := readDNSFrame(t, conn); binary.BigEndian.Uint16(reply[6:8]) != 0 {
				t.Fatal("IPv6 answer not suppressed")
			}
			if requests.Load() != before {
				t.Fatal("AAAA query escaped local IPv4-only policy")
			}
			_ = closeDialer()
			if _, err := conn.Read(make([]byte, 1)); err == nil {
				t.Fatal("DNS stream survived Stop")
			}
			if c, err := dial(context.Background(), metadata); err == nil {
				c.Close()
				t.Fatal("DNS admitted after Stop")
			}
		})
	}
}

func readDNSFrame(t *testing.T, c net.Conn) []byte {
	t.Helper()
	var header [2]byte
	if _, err := io.ReadFull(c, header[:]); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, binary.BigEndian.Uint16(header[:]))
	if _, err := io.ReadFull(c, reply); err != nil {
		t.Fatal(err)
	}
	return reply
}

func TestTCPDNSRejectsShortFrameAndHonorsDeadline(t *testing.T) {
	d := &httpsConnectDialer{}
	defer d.Close()
	m := &M.Metadata{DstIP: netip.MustParseAddr("10.77.0.2"), DstPort: 53}
	c, err := d.DialContext(context.Background(), m)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetReadDeadline(time.Now().Add(20 * time.Millisecond))
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Fatal("read did not honor deadline")
	}
	_ = c.SetDeadline(time.Now().Add(time.Second))
	if _, err := c.Write([]byte{0, 11}); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Fatal("short DNS frame accepted")
	}
}

func TestTCPDNSHalfClosePreservesQueuedAnswers(t *testing.T) {
	d := &httpsConnectDialer{}
	defer d.Close()
	c, err := d.DialContext(context.Background(), &M.Metadata{DstIP: netip.MustParseAddr("10.77.0.2"), DstPort: 53})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(time.Second))
	query, _, _ := buildAQuery("example.test")
	binary.BigEndian.PutUint16(query[len(query)-4:len(query)-2], 28)
	for i := 0; i < 3; i++ {
		if _, err := c.Write(append([]byte{0, byte(len(query))}, query...)); err != nil {
			t.Fatal(err)
		}
	}
	if err := closeConnWrite(c); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		readDNSFrame(t, c)
	}
	if _, err := c.Read(make([]byte, 1)); err != io.EOF && err != io.ErrClosedPipe {
		t.Fatalf("expected closed stream after responses, got %v", err)
	}
}
