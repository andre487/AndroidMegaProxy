package mobile

import (
	"encoding/json"
	"sync"
	"sync/atomic"
	"time"
)

const connectionSampleWindow = 50

type connectionStats struct {
	downloadBytes atomic.Uint64
	uploadBytes   atomic.Uint64
	totalOutcomes atomic.Uint64

	mu             sync.Mutex
	latencyMillis  float64
	latencySamples uint64
	latencyAt      time.Time
	outcomes       [connectionSampleWindow]bool
	outcomeCount   int
	outcomeNext    int
}

var telemetry connectionStats

type statsSnapshot struct {
	DownloadBytes        uint64  `json:"downloadBytes"`
	UploadBytes          uint64  `json:"uploadBytes"`
	ProxyLatencyMillis   float64 `json:"proxyLatencyMillis"`
	ProxyLatencyAtMillis int64   `json:"proxyLatencyAtMillis"`
	ConnectionErrorRate  float64 `json:"connectionErrorRate"`
	ConnectionSamples    int     `json:"connectionSamples"`
	TotalOutcomes        uint64  `json:"totalOutcomes"`
}

func resetStats() {
	telemetry.downloadBytes.Store(0)
	telemetry.uploadBytes.Store(0)
	telemetry.totalOutcomes.Store(0)
	telemetry.mu.Lock()
	telemetry.latencyMillis = 0
	telemetry.latencySamples = 0
	telemetry.latencyAt = time.Time{}
	telemetry.outcomes = [connectionSampleWindow]bool{}
	telemetry.outcomeCount = 0
	telemetry.outcomeNext = 0
	telemetry.mu.Unlock()
}

func recordProxyLatency(elapsed time.Duration) {
	millis := float64(elapsed.Microseconds()) / 1000
	telemetry.mu.Lock()
	if telemetry.latencySamples == 0 {
		telemetry.latencyMillis = millis
	} else {
		const alpha = 0.2
		telemetry.latencyMillis = alpha*millis + (1-alpha)*telemetry.latencyMillis
	}
	telemetry.latencySamples++
	telemetry.latencyAt = time.Now()
	telemetry.mu.Unlock()
}

func recordConnectionOutcome(success bool) {
	telemetry.totalOutcomes.Add(1)
	telemetry.mu.Lock()
	telemetry.outcomes[telemetry.outcomeNext] = success
	telemetry.outcomeNext = (telemetry.outcomeNext + 1) % connectionSampleWindow
	if telemetry.outcomeCount < connectionSampleWindow {
		telemetry.outcomeCount++
	}
	telemetry.mu.Unlock()
}

func snapshotStats() statsSnapshot {
	result := statsSnapshot{
		DownloadBytes: telemetry.downloadBytes.Load(),
		UploadBytes:   telemetry.uploadBytes.Load(),
		TotalOutcomes: telemetry.totalOutcomes.Load(),
	}
	telemetry.mu.Lock()
	result.ProxyLatencyMillis = telemetry.latencyMillis
	if !telemetry.latencyAt.IsZero() {
		result.ProxyLatencyAtMillis = telemetry.latencyAt.UnixMilli()
	}
	result.ConnectionSamples = telemetry.outcomeCount
	failures := 0
	for index := 0; index < telemetry.outcomeCount; index++ {
		if !telemetry.outcomes[index] {
			failures++
		}
	}
	if telemetry.outcomeCount > 0 {
		result.ConnectionErrorRate = float64(failures) / float64(telemetry.outcomeCount)
	}
	telemetry.mu.Unlock()
	return result
}

// GetStats returns a lightweight JSON snapshot for the Android UI.
func GetStats() string {
	encoded, _ := json.Marshal(snapshotStats())
	return string(encoded)
}
