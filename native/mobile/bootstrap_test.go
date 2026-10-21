package mobile

import (
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"
	"time"
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

func TestBootstrapFastResolverWinsAndCancelsSlowRequest(t *testing.T) {
	cancelled := make(chan struct{})
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		<-r.Context().Done()
		close(cancelled)
	}))
	defer slow.Close()
	fast := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		query, _ := io.ReadAll(r.Body)
		query[2] |= 0x80
		binary.BigEndian.PutUint16(query[6:8], 1)
		query = append(query, 0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 203, 0, 113, 7)
		w.Write(query)
	}))
	defer fast.Close()
	saved := bootstrapResolvers
	bootstrapResolvers = []bootstrapResolver{
		{name: "slow", address: slow.Listener.Addr().String(), url: slow.URL},
		{name: "fast", address: fast.Listener.Addr().String(), url: fast.URL},
	}
	defer func() { bootstrapResolvers = saved }()
	ip, err := ResolveProxy("proxy.test", &jumpTestProtector{}, nil)
	if err != nil || ip != "203.0.113.7" {
		t.Fatalf("ip=%q err=%v", ip, err)
	}
	select {
	case <-cancelled:
	case <-time.After(time.Second):
		t.Fatal("slow bootstrap was not cancelled")
	}
}
