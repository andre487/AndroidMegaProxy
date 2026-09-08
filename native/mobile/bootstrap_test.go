package mobile

import (
	"net"
	"net/url"
	"testing"
)

func TestBootstrapResolversUseDirectHTTPSAddresses(t *testing.T) {
	if len(bootstrapResolvers) < 3 {
		t.Fatalf("bootstrap resolver count = %d, want failover", len(bootstrapResolvers))
	}
	for _, resolver := range bootstrapResolvers {
		parsed, err := url.Parse(resolver.url)
		if err != nil || parsed.Scheme != "https" || parsed.Hostname() == "" {
			t.Fatalf("invalid resolver URL %q", resolver.url)
		}
		host, port, err := net.SplitHostPort(resolver.address)
		if err != nil || net.ParseIP(host) == nil || port != "443" {
			t.Fatalf("invalid direct resolver address %q", resolver.address)
		}
	}
}

func TestBootstrapIncludesYandexRedundancy(t *testing.T) {
	count := 0
	for _, resolver := range bootstrapResolvers {
		if resolver.name == "yandex_primary" || resolver.name == "yandex_secondary" {
			count++
		}
	}
	if count != 2 {
		t.Fatalf("Yandex resolver count = %d, want 2", count)
	}
}

func TestResolveProxyLiteralDoesNotRequireDNS(t *testing.T) {
	for _, host := range []string{"203.0.113.7", " 203.0.113.7 "} {
		ip, err := ResolveProxy(host, nil, nil)
		if err != nil || ip != "203.0.113.7" {
			t.Fatalf("literal: %q %v", ip, err)
		}
	}
}
