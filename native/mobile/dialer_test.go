package mobile

import "testing"

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

func TestPublicAndHostnameTargetsAreNotLocal(t *testing.T) {
	public := []string{"1.1.1.1:443", "8.8.8.8:53", "example.com:443", "[2606:4700:4700::1111]:443"}
	for _, target := range public {
		if isLocalNetworkTarget(target) {
			t.Errorf("expected non-local target: %s", target)
		}
	}
}
