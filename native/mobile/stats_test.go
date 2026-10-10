package mobile

import (
	"errors"
	"net"
	"testing"
	"time"

	quic "github.com/refraction-networking/uquic"
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

func TestQUICStatsWindowAndIsolation(t *testing.T) {
	s := resetStats()
	now := time.Unix(1000, 0)
	value := quic.ConnectionStats{SmoothedRTT: 42 * time.Millisecond, PacketsLost: 3}
	c := &measuredQUICConn{stats: s, readInfo: func() quic.ConnectionStats { return value }}
	s.quicSessions[c] = struct{}{}
	first := s.snapshot(now)
	if first.QUICRTTMillis == nil || *first.QUICRTTMillis != 42 || first.QUICPacketsLost == nil || *first.QUICPacketsLost != 3 {
		t.Fatalf("first: %+v", first)
	}
	if first.TCPRTTMillis != nil || first.TCPRetransmits != nil {
		t.Fatal("QUIC reported TCP metrics")
	}
	value.PacketsLost = 5
	if got := s.snapshot(now.Add(time.Second)); *got.QUICPacketsLost != 5 {
		t.Fatal("counted cumulative sample twice")
	}
	if got := s.snapshot(now.Add(300 * time.Second)); *got.QUICPacketsLost != 2 {
		t.Fatal("first bucket did not expire")
	}
	if got := s.snapshot(now.Add(301 * time.Second)); *got.QUICPacketsLost != 0 {
		t.Fatal("expired losses retained")
	}
	// An upstream correction must never turn unsigned subtraction into huge losses.
	value.PacketsLost = 4
	if got := s.snapshot(now.Add(302 * time.Second)); *got.QUICPacketsLost != 0 {
		t.Fatal("counter decrease underflowed")
	}
	fresh := resetStats()
	value.PacketsLost = 6
	s.snapshot(now.Add(303 * time.Second))
	if got := fresh.snapshot(now); got.QUICRTTMillis != nil || got.QUICPacketsLost != nil {
		t.Fatal("old generation contaminated new VPN")
	}
	delete(s.quicSessions, c)
	if got := s.snapshot(now.Add(603 * time.Second)); got.QUICRTTMillis != nil || got.QUICPacketsLost != nil {
		t.Fatal("stale QUIC metrics retained")
	}
}

func TestQUICStatsAverageFinalSampleAndUnavailable(t *testing.T) {
	s := resetStats()
	if got := s.snapshot(time.Now()); got.QUICRTTMillis != nil || got.QUICPacketsLost != nil {
		t.Fatal("unavailable is not zero")
	}
	value := quic.ConnectionStats{SmoothedRTT: 42 * time.Millisecond}
	first := &measuredQUICConn{stats: s, readInfo: func() quic.ConnectionStats { return value }}
	second := &measuredQUICConn{stats: s, readInfo: func() quic.ConnectionStats { return quic.ConnectionStats{SmoothedRTT: 100 * time.Millisecond} }}
	s.quicSessions[first], s.quicSessions[second] = struct{}{}, struct{}{}
	if got := s.snapshot(time.Now()); *got.QUICRTTMillis != 71 || *got.QUICPacketsLost != 0 {
		t.Fatal("mean RTT / zero loss incorrect")
	}
	value.PacketsLost = 2
	first.Close()
	first.Close()
	second.Close()
	got := s.snapshot(time.Now())
	if len(s.quicSessions) != 0 || got.QUICRTTMillis != nil || *got.QUICPacketsLost != 2 {
		t.Fatal("final sample lost or counted twice")
	}
}
