package mobile

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net"
	"strings"
	"testing"
	"time"
)

const validNativeConfig = `{"host":"proxy.example","dialHost":"192.0.2.1","port":443,"username":"u","password":"p","profile":"CHROME_ANDROID","dohUrl":"https://dns.google/dns-query"}`

func TestNativeConfigLimits(t *testing.T) {
	for _, field := range []string{"sshKeepaliveSeconds", "sshRotationMinutes", "sshRotationMb"} {
		for _, value := range []int64{-1, 9223372036854775807} {
			var fields map[string]any
			if err := json.Unmarshal([]byte(validNativeConfig), &fields); err != nil {
				t.Fatal(err)
			}
			fields[field] = value
			raw, err := json.Marshal(fields)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := parseConfig(string(raw)); err == nil {
				t.Fatalf("accepted %s=%d", field, value)
			}
		}
	}
	raw := strings.TrimSuffix(validNativeConfig, "}") + `,"unused":"` + strings.Repeat("x", 1024*1024) + `"}`
	if _, err := parseConfig(raw); err == nil {
		t.Fatal("accepted oversized JSON")
	}
	if _, err := TestConnection(validNativeConfig, nil, nil); err == nil || !strings.Contains(err.Error(), "protector") {
		t.Fatalf("missing protector: %v", err)
	}
	if _, _, err := buildAQuery(strings.Repeat("a.", 200)); err == nil {
		t.Fatal("accepted oversized DNS name")
	}
}

func TestDoHReplyQueueCannotBlockWriters(t *testing.T) {
	c := newDoHPacketConnWithClient(config{}, nil, nil, "https://dns.example/query", nil, nil)
	defer c.Close()
	query, _, err := buildAQuery("example.com")
	if err != nil {
		t.Fatal(err)
	}
	binary.BigEndian.PutUint16(query[len(query)-4:], 28)
	done := make(chan error, 1)
	go func() {
		for i := 0; i < 100; i++ {
			if _, err := c.WriteTo(query, dnsAddr("dns")); err != nil {
				done <- err
				return
			}
		}
		done <- nil
	}()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("full reply queue blocked DNS writers")
	}
	if len(c.replies) != cap(c.replies) {
		t.Fatal("reply queue not bounded at capacity")
	}
	if _, err := c.WriteTo(make([]byte, 65536), dnsAddr("dns")); err == nil {
		t.Fatal("accepted oversized DNS packet")
	}
}

func TestDoHDeadlinesWakePendingOperations(t *testing.T) {
	c := newDoHPacketConnWithClient(config{AllowIPv6: true}, nil, nil, "https://dns.example/query", nil, make(chan struct{}, 1))
	defer c.Close()
	read := make(chan error, 1)
	go func() { _, _, err := c.ReadFrom(make([]byte, 512)); read <- err }()
	if err := c.SetReadDeadline(time.Now().Add(20 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-read:
		if !isTimeout(err) {
			t.Fatalf("read: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("pending read ignored updated deadline")
	}
	if err := c.SetReadDeadline(time.Time{}); err != nil {
		t.Fatal(err)
	}
	c.deliver(dnsReply{payload: []byte{1}, addr: dnsAddr("dns")})
	if n, _, err := c.ReadFrom(make([]byte, 512)); err != nil || n != 1 {
		t.Fatalf("cleared deadline: %d %v", n, err)
	}
	c.inFlight <- struct{}{} // Hold every provider slot, without making a network request.
	query, _, err := buildAQuery("example.com")
	if err != nil {
		t.Fatal(err)
	}
	write := make(chan error, 1)
	go func() { _, err := c.WriteTo(query, dnsAddr("dns")); write <- err }()
	if err := c.SetWriteDeadline(time.Now().Add(20 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-write:
		if !isTimeout(err) {
			t.Fatalf("write: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("pending write ignored deadline")
	}
}

func TestClosedPacketDeadlineCannotRetainNewTimers(t *testing.T) {
	var d packetDeadline
	d.stop()
	d.set(time.Now().Add(time.Hour))
	if d.timer != nil {
		t.Fatal("closed deadline installed a timer")
	}
	select {
	case <-d.wait():
	default:
		t.Fatal("closed deadline did not signal")
	}
}

func FuzzNativeParsers(f *testing.F) {
	f.Add([]byte(validNativeConfig))
	f.Add([]byte("771,4865,0,29,0"))
	f.Add([]byte{0, 1, 128, 0, 0, 1, 0, 0, 0, 0, 0, 0})
	f.Fuzz(func(t *testing.T, data []byte) {
		if len(data) > 1024*1024 {
			t.Skip()
		}
		_, _ = parseConfig(string(data))
		_, _ = parseJA3(string(data))
		_, _, _ = buildAQuery(string(data))
		_, _ = parseAResponse(data, 1)
		_, _, _ = emptyAAAAResponse(data)
		_, _ = skipDNSName(data, -1)
		_, _ = skipDNSName(data, 0)
	})
}

// Keep this fixture compile-checked against the production packet interface.
var _ net.PacketConn = (*dohPacketConn)(nil)

func TestWrappedTimeoutClassification(t *testing.T) {
	if got := errorClass(fmt.Errorf("dial proxy: %w", timeoutError{})); got != "timeout" {
		t.Fatalf("wrapped timeout classified as %s", got)
	}
}
