package mobile

import (
	"testing"
	"time"
)

func TestConnectionStatsRollingWindow(t *testing.T) {
	resetStats()
	for index := 0; index < connectionSampleWindow+10; index++ {
		recordConnectionOutcome(index%2 == 0)
	}
	snapshot := snapshotStats()
	if snapshot.ConnectionSamples != connectionSampleWindow {
		t.Fatalf("samples = %d, want %d", snapshot.ConnectionSamples, connectionSampleWindow)
	}
	if snapshot.TotalOutcomes != connectionSampleWindow+10 {
		t.Fatalf("total outcomes = %d, want %d", snapshot.TotalOutcomes, connectionSampleWindow+10)
	}
	if snapshot.ConnectionErrorRate != 0.5 {
		t.Fatalf("error rate = %f, want 0.5", snapshot.ConnectionErrorRate)
	}
}

func TestConnectionStatsCountersAndLatency(t *testing.T) {
	resetStats()
	telemetry.downloadBytes.Add(2048)
	telemetry.uploadBytes.Add(1024)
	recordProxyLatency(80 * time.Millisecond)
	recordProxyLatency(100 * time.Millisecond)
	snapshot := snapshotStats()
	if snapshot.DownloadBytes != 2048 || snapshot.UploadBytes != 1024 {
		t.Fatalf("unexpected counters: %+v", snapshot)
	}
	if snapshot.ProxyLatencyMillis != 84 {
		t.Fatalf("EWMA latency = %f, want 84", snapshot.ProxyLatencyMillis)
	}
	if snapshot.ProxyLatencyAtMillis == 0 {
		t.Fatal("latency timestamp was not recorded")
	}
}
