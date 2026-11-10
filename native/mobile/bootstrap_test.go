package mobile

import (
	"encoding/binary"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestBootstrapAAAAFallback(t *testing.T) {
	for _, mode := range []string{"dual_stack", "aaaa_only", "empty", "servfail", "http_error", "truncated", "wrong_id", "bad_aaaa_length"} {
		t.Run(mode, func(t *testing.T) {
			var mu sync.Mutex
			var types []uint16
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				query, _ := io.ReadAll(r.Body)
				kind := binary.BigEndian.Uint16(query[len(query)-4:])
				mu.Lock()
				types = append(types, kind)
				mu.Unlock()
				query[2] |= 0x80
				switch mode {
				case "http_error":
					w.WriteHeader(503)
					return
				case "servfail":
					query[3] = 2
				case "truncated":
					query[2] |= 2
				case "wrong_id":
					query[0] ^= 1
				}
				var address net.IP
				if mode == "dual_stack" && kind == 1 {
					address = net.IPv4(203, 0, 113, 7).To4()
				}
				if (mode == "aaaa_only" || mode == "bad_aaaa_length") && kind == 28 {
					address = net.ParseIP("::1").To16()
				}
				if mode == "bad_aaaa_length" && kind == 28 {
					address = address[:4]
				}
				if address != nil {
					binary.BigEndian.PutUint16(query[6:8], 1)
					query = append(query, 0xc0, 0x0c, 0, byte(kind), 0, 1, 0, 0, 0, 60, 0, byte(len(address)))
					query = append(query, address...)
				}
				w.Write(query)
			}))
			defer server.Close()
			saved := bootstrapResolvers
			bootstrapResolvers = []bootstrapResolver{{name: "fixture", address: server.Listener.Addr().String(), url: server.URL}}
			defer func() { bootstrapResolvers = saved }()
			logs := &diagnosticRecorder{}
			ip, err := ResolveProxy("proxy.test", &jumpTestProtector{}, logs)
			success := mode == "dual_stack" || mode == "aaaa_only"
			if (err == nil) != success {
				t.Fatalf("result %q: %v", ip, err)
			}
			mu.Lock()
			gotTypes := append([]uint16(nil), types...)
			mu.Unlock()
			fallback := mode == "aaaa_only" || mode == "empty" || mode == "bad_aaaa_length"
			wantTypes := []uint16{1}
			if fallback {
				wantTypes = append(wantTypes, 28)
			}
			if !slices.Equal(gotTypes, wantTypes) {
				t.Fatalf("query types: %v", gotTypes)
			}
			if mode == "dual_stack" && ip != "203.0.113.7" {
				t.Fatal("IPv4 preference lost")
			}
			if mode == "aaaa_only" {
				if !strings.Contains(logs.text(), "family=ipv6") {
					t.Fatal("missing IPv6 bootstrap diagnostic")
				}
				if ip != "::1" {
					t.Fatalf("AAAA result %q", ip)
				}
				listener, err := net.Listen("tcp6", "[::1]:0")
				if err != nil {
					t.Fatal(err)
				}
				defer listener.Close()
				cfg := config{DialHost: ip, Port: listener.Addr().(*net.TCPAddr).Port}
				conn, err := net.DialTimeout("tcp", cfg.address(), time.Second)
				if err != nil {
					t.Fatal(err)
				}
				conn.Close()
			}
		})
	}
}

func TestBootstrapIPv6LiteralDoesNotUseDNS(t *testing.T) {
	for _, host := range []string{"2001:db8::1", "[2001:db8::1]"} {
		got, err := ResolveProxy(host, nil, nil)
		if err != nil || got != "2001:db8::1" {
			t.Fatalf("literal bootstrap: %q %v", got, err)
		}
	}
}

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
	empty := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		query, _ := io.ReadAll(r.Body)
		if binary.BigEndian.Uint16(query[len(query)-4:]) != 1 {
			t.Error("AAAA requested before checking other A resolvers")
		}
		query[2] |= 0x80
		w.Write(query)
	}))
	defer empty.Close()
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
		{name: "empty", address: empty.Listener.Addr().String(), url: empty.URL},
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
