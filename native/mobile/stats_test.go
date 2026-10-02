package mobile

import (
	"errors"
	"net"
	"testing"
	"time"
)

func TestTCPStatsWindowAndIsolation(t *testing.T) {
	s := resetStats()
	now := time.Unix(1000, 0)
	value := tcpSample{rttMicros: 42000, retransmits: 3}
	c := &measuredTCPConn{stats: s, readInfo: func() (tcpSample, error) { return value, nil }}
	s.sockets[c] = struct{}{}
	s.downloadBytes.Add(2048)
	first := s.snapshot(now)
	if first.TCPRTTMillis == nil || *first.TCPRTTMillis != 42 || *first.TCPRetransmits != 3 || first.DownloadBytes != 2048 {
		t.Fatalf("first: %+v", first)
	}
	value.retransmits = 5
	second := s.snapshot(now.Add(time.Second))
	if *second.TCPRetransmits != 5 {
		t.Fatal("counter must count deltas, not cumulative samples")
	}
	if *s.snapshot(now.Add(299 * time.Second)).TCPRetransmits != 5 {
		t.Fatal("expired early")
	}
	if *s.snapshot(now.Add(300 * time.Second)).TCPRetransmits != 2 {
		t.Fatal("five-minute window did not expire first bucket")
	}
	if *s.snapshot(now.Add(301 * time.Second)).TCPRetransmits != 0 {
		t.Fatal("old retransmits retained")
	}
	fresh := resetStats()
	value.retransmits++
	s.snapshot(now.Add(302 * time.Second))
	if got := fresh.snapshot(now); got.TCPRTTMillis != nil || got.TCPRetransmits != nil || got.DownloadBytes != 0 {
		t.Fatal("old generation contaminated new VPN")
	}
	delete(s.sockets, c)
	if s.snapshot(now.Add(603*time.Second)).TCPRetransmits != nil {
		t.Fatal("stale samples reported as current")
	}
}

func TestTCPStatsUnavailableAndClose(t *testing.T) {
	s := resetStats()
	left, right := net.Pipe()
	defer right.Close()
	count := uint32(0)
	c := &measuredTCPConn{Conn: left, stats: s, readInfo: func() (tcpSample, error) { return tcpSample{rttMicros: 1000, retransmits: count}, nil }}
	s.sockets[c] = struct{}{}
	s.snapshot(time.Now())
	count = 2
	c.Close()
	c.Close()
	got := s.snapshot(time.Now())
	if len(s.sockets) != 0 || got.TCPRTTMillis != nil || got.TCPRetransmits == nil || *got.TCPRetransmits != 2 {
		t.Fatal("final sample lost or counted twice")
	}
	s = resetStats()
	c = &measuredTCPConn{stats: s, readInfo: func() (tcpSample, error) { return tcpSample{}, errors.New("unsupported") }}
	s.sockets[c] = struct{}{}
	got = s.snapshot(time.Now())
	if got.TCPRTTMillis != nil || got.TCPRetransmits != nil {
		t.Fatal("unavailable is not zero")
	}
}

func TestDiagnosticTrafficDoesNotChangeVPNStats(t *testing.T) {
	stats := resetStats()
	left, right := net.Pipe()
	defer left.Close()
	defer right.Close()
	done := make(chan struct{})
	go func() { defer close(done); right.Write([]byte("test")) }()
	c := &diagnosticConn{Conn: left}
	b := make([]byte, 4)
	if _, err := c.Read(b); err != nil {
		t.Fatal(err)
	}
	<-done
	if stats.downloadBytes.Load() != 0 {
		t.Fatal("diagnostic polluted VPN counters")
	}
}
