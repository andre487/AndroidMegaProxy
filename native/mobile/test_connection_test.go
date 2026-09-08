package mobile

import (
	"bufio"
	"context"
	"errors"
	"net"
	"net/http"
	"testing"
	"time"
)

func TestParseIPAddress(t *testing.T) {
	for _, value := range []string{"203.0.113.7\n", "2001:db8::7"} {
		if parsed, ok := parseIPAddress(value); !ok || parsed == "" {
			t.Fatalf("parseIPAddress(%q) = %q, %v", value, parsed, ok)
		}
	}
	for _, value := range []string{"", "not-an-ip", "203.0.113.7 extra"} {
		if _, ok := parseIPAddress(value); ok {
			t.Fatalf("parseIPAddress(%q) accepted invalid input", value)
		}
	}
}

func TestParseCountryCode(t *testing.T) {
	if value, ok := parseCountryCode(" nl\n"); !ok || value != "NL" {
		t.Fatalf("parseCountryCode returned %q, %v", value, ok)
	}
	for _, value := range []string{"", "NLD", "N1", "🇳🇱"} {
		if _, ok := parseCountryCode(value); ok {
			t.Fatalf("parseCountryCode(%q) accepted invalid input", value)
		}
	}
}

func TestParseCountryJSON(t *testing.T) {
	if value, ok := parseCountryJSON(`{"ip":"203.0.113.7","country":"nl"}`); !ok || value != "NL" {
		t.Fatalf("parseCountryJSON returned %q, %v", value, ok)
	}
	for _, value := range []string{`{"country":""}`, `{"country":"NLD"}`, `not-json`} {
		if _, ok := parseCountryJSON(value); ok {
			t.Fatalf("parseCountryJSON(%q) accepted invalid input", value)
		}
	}
}

func TestLookupEndpointValueFallsBackAfterErrorsAndInvalidResponses(t *testing.T) {
	endpoints := []testEndpoint{
		{host: "unavailable.example", parse: parseCountryCode},
		{host: "invalid.example", parse: parseCountryCode},
		{host: "working.example", parse: parseCountryCode},
	}
	attempts := 0
	value, err := lookupEndpointValue(context.Background(), nil, "country", endpoints, func(_ context.Context, endpoint testEndpoint) (string, error) {
		attempts++
		switch endpoint.host {
		case "unavailable.example":
			return "", errors.New("unavailable")
		case "invalid.example":
			return "not-a-country", nil
		default:
			return "nl", nil
		}
	})
	if err != nil || value != "NL" || attempts != 3 {
		t.Fatalf("lookupEndpointValue returned value=%q err=%v attempts=%d", value, err, attempts)
	}
}

func TestLookupEndpointValueFailsAfterEveryProvider(t *testing.T) {
	endpoints := []testEndpoint{{host: "one", parse: parseIPAddress}, {host: "two", parse: parseIPAddress}}
	attempts := 0
	value, err := lookupEndpointValue(context.Background(), nil, "ip", endpoints, func(_ context.Context, _ testEndpoint) (string, error) {
		attempts++
		return "invalid", nil
	})
	if err == nil || value != "" || attempts != len(endpoints) {
		t.Fatalf("lookupEndpointValue returned value=%q err=%v attempts=%d", value, err, attempts)
	}
}

func TestHTTPExchangeCancellationInterruptsResponse(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := testHTTPExchange(ctx, client, "example.com", "/", true); done <- err }()
	if _, err := http.ReadRequest(bufio.NewReader(server)); err != nil {
		t.Fatal(err)
	}
	cancel() // Peer has accepted the request but never sends response headers.
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("cancellation succeeded without an error")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("HTTP read ignored cancellation")
	}
}
