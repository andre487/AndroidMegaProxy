package mobile

import (
	"bytes"
	"io"
	"testing"
)

func TestLocalNetworkTargets(t *testing.T) {
	local := []string{
		"10.0.0.1:443",
		"172.16.0.1:80",
		"192.168.1.20:8080",
		"127.0.0.1:3000",
		"169.254.1.1:22",
		"[fd00::1]:443",
		"[fe80::1]:443",
	}
	for _, target := range local {
		if !isLocalNetworkTarget(target) {
			t.Errorf("expected local target: %s", target)
		}
	}
}

func TestLimitedHeaderReaderRejectsOversizedHeaders(t *testing.T) {
	raw := bytes.Repeat([]byte{'x'}, 32)
	reader := &limitedHeaderReader{reader: bytes.NewReader(raw), remaining: 16}
	if _, err := io.ReadAll(reader); err == nil {
		t.Fatal("expected oversized CONNECT headers to fail")
	}
}

func TestLimitedHeaderReaderBecomesUnlimitedAfterHeaders(t *testing.T) {
	body := bytes.Repeat([]byte{'b'}, 128)
	raw := append([]byte("HTTP/1.1 200 OK\r\n\r\n"), body...)
	reader := &limitedHeaderReader{reader: bytes.NewReader(raw), remaining: 64}
	got, err := io.ReadAll(reader)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, raw) {
		t.Fatalf("unexpected data length: got %d, want %d", len(got), len(raw))
	}
}

func TestPublicAndHostnameTargetsAreNotLocal(t *testing.T) {
	public := []string{"1.1.1.1:443", "8.8.8.8:53", "example.com:443", "[2606:4700:4700::1111]:443"}
	for _, target := range public {
		if isLocalNetworkTarget(target) {
			t.Errorf("expected non-local target: %s", target)
		}
	}
}
