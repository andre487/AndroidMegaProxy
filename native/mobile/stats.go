package mobile

import (
	"context"
	"encoding/json"
	"net"
	"sync"
	"sync/atomic"
	"time"

	quic "github.com/refraction-networking/uquic"
)

// Each VPN generation owns its sockets and counters. Diagnostics have no stats.
type connectionStats struct {
	downloadBytes  atomic.Uint64
	uploadBytes    atomic.Uint64
	mu             sync.Mutex
	sockets        map[*measuredTCPConn]struct{}
	retransmits    [300]counterBucket
	lastSample     time.Time
	quicSessions   map[*measuredQUICConn]struct{}
	quicLosses     [300]counterBucket
	lastQUICSample time.Time
	done           chan struct{}
}
type counterBucket struct {
	second int64
	count  uint64
}
type tcpSample struct{ rttMicros, retransmits uint32 }

var telemetry atomic.Pointer[connectionStats]

type statsSnapshot struct {
	DownloadBytes   uint64   `json:"downloadBytes"`
	UploadBytes     uint64   `json:"uploadBytes"`
	TCPRTTMillis    *float64 `json:"tcpRttMillis"`
	TCPRetransmits  *uint64  `json:"tcpRetransmits"`
	QUICRTTMillis   *float64 `json:"quicRttMillis"`
	QUICPacketsLost *uint64  `json:"quicPacketsLost"`
}

func resetStats() *connectionStats {
	s := &connectionStats{sockets: make(map[*measuredTCPConn]struct{}), quicSessions: make(map[*measuredQUICConn]struct{}), done: make(chan struct{})}
	telemetry.Store(s)
	return s
}

type measuredTCPConn struct {
	net.Conn
	stats    *connectionStats
	readInfo func() (tcpSample, error)
	previous uint32
	once     sync.Once
}

func dialMeasuredTCP(ctx context.Context, dialer *net.Dialer, address string, stats *connectionStats) (net.Conn, error) {
	conn, err := dialer.DialContext(ctx, "tcp", address)
	if err != nil || stats == nil {
		return conn, err
	}
	c := &measuredTCPConn{Conn: conn, stats: stats, readInfo: func() (tcpSample, error) { return readTCPInfo(conn) }}
	stats.mu.Lock()
	stats.sockets[c] = struct{}{}
	c.sample(time.Now())
	stats.mu.Unlock()
	return c, nil
}

// Called under stats.mu. Count deltas once, including a final sample before close.
func (c *measuredTCPConn) sample(now time.Time) (tcpSample, bool) {
	info, err := c.readInfo()
	if err != nil {
		return tcpSample{}, false
	}
	delta := info.retransmits - c.previous // uint32 kernel counter can wrap.
	c.previous = info.retransmits
	second := now.Unix()
	bucket := &c.stats.retransmits[second%300]
	if bucket.second != second {
		*bucket = counterBucket{second: second}
	}
	bucket.count += uint64(delta)
	c.stats.lastSample = now
	return info, true
}

func (c *measuredTCPConn) Close() error {
	c.once.Do(func() {
		c.stats.mu.Lock()
		c.sample(time.Now())
		delete(c.stats.sockets, c)
		c.stats.mu.Unlock()
	})
	return c.Conn.Close()
}

// One measurement per physical QUIC session, independent of multiplexed streams.
type measuredQUICConn struct {
	stats    *connectionStats
	readInfo func() quic.ConnectionStats
	previous uint64
	once     sync.Once
}

func (s *connectionStats) trackQUIC(conn *quic.Conn) *measuredQUICConn {
	c := &measuredQUICConn{stats: s, readInfo: conn.ConnectionStats}
	s.mu.Lock()
	if s.quicSessions == nil {
		s.quicSessions = make(map[*measuredQUICConn]struct{})
	}
	s.quicSessions[c] = struct{}{}
	c.sample(time.Now())
	s.mu.Unlock()
	context.AfterFunc(conn.Context(), c.Close)
	return c
}

// Called under stats.mu. uQUIC currently increments this counter on loss detection.
func (c *measuredQUICConn) sample(now time.Time) quic.ConnectionStats {
	info := c.readInfo()
	var delta uint64
	// Guard against a corrected/decreased counter instead of unsigned underflow.
	if info.PacketsLost >= c.previous {
		delta = info.PacketsLost - c.previous
	}
	c.previous = info.PacketsLost
	second := now.Unix()
	bucket := &c.stats.quicLosses[second%300]
	if bucket.second != second {
		*bucket = counterBucket{second: second}
	}
	bucket.count += delta
	c.stats.lastQUICSample = now
	return info
}

func (c *measuredQUICConn) Close() {
	c.once.Do(func() {
		c.stats.mu.Lock()
		defer c.stats.mu.Unlock()
		c.sample(time.Now())
		delete(c.stats.quicSessions, c)
	})
}

func (s *connectionStats) snapshot(now time.Time) statsSnapshot {
	result := statsSnapshot{DownloadBytes: s.downloadBytes.Load(), UploadBytes: s.uploadBytes.Load()}
	s.mu.Lock()
	defer s.mu.Unlock()
	var rttSum uint64
	var rttCount uint64
	for c := range s.sockets {
		if info, ok := c.sample(now); ok && info.rttMicros > 0 {
			rttSum += uint64(info.rttMicros)
			rttCount++
		}
	}
	if rttCount > 0 {
		rtt := float64(rttSum) / float64(rttCount) / 1000
		result.TCPRTTMillis = &rtt
	}
	if !s.lastSample.IsZero() && now.Sub(s.lastSample) < 5*time.Minute {
		var retransmits uint64
		for _, b := range s.retransmits {
			if age := now.Unix() - b.second; age >= 0 && age < 300 {
				retransmits += b.count
			}
		}
		result.TCPRetransmits = &retransmits
	}
	var quicRTTSum time.Duration
	var quicRTTCount int
	for c := range s.quicSessions {
		info := c.sample(now)
		if info.SmoothedRTT > 0 {
			quicRTTSum += info.SmoothedRTT
			quicRTTCount++
		}
	}
	if quicRTTCount > 0 {
		rtt := float64(quicRTTSum) / float64(quicRTTCount) / float64(time.Millisecond)
		result.QUICRTTMillis = &rtt
	}
	if !s.lastQUICSample.IsZero() && now.Sub(s.lastQUICSample) < 5*time.Minute {
		var lost uint64
		for _, b := range s.quicLosses {
			if age := now.Unix() - b.second; age >= 0 && age < 300 {
				lost += b.count
			}
		}
		result.QUICPacketsLost = &lost
	}
	return result
}

func snapshotStats() statsSnapshot {
	if s := telemetry.Load(); s != nil {
		return s.snapshot(time.Now())
	}
	return statsSnapshot{}
}

// GetStats returns passive TCP / QUIC metrics without generating probe traffic.
func GetStats() string { encoded, _ := json.Marshal(snapshotStats()); return string(encoded) }

// Poll independently of the UI so background VPN traffic stays in its time window.
func (s *connectionStats) run() {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-s.done:
			return
		case <-ticker.C:
			s.snapshot(time.Now())
		}
	}
}
