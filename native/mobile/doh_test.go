package mobile

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestEmptyAAAAResponseSuppressesIPv6Answer(t *testing.T) {
	query, id, err := buildAQuery("example.com")
	if err != nil {
		t.Fatal(err)
	}
	binary.BigEndian.PutUint16(query[len(query)-4:len(query)-2], 28)

	response, isAAAA, err := emptyAAAAResponse(query)
	if err != nil {
		t.Fatal(err)
	}
	if !isAAAA {
		t.Fatal("expected an AAAA query")
	}
	if binary.BigEndian.Uint16(response[0:2]) != id || binary.BigEndian.Uint16(response[6:8]) != 0 {
		t.Fatalf("unexpected empty response: %x", response)
	}
	if binary.BigEndian.Uint16(response[2:4])&0x8000 == 0 {
		t.Fatal("expected the DNS response bit")
	}
}

func TestEmptyAAAAResponseLeavesAQueryUntouched(t *testing.T) {
	query, _, err := buildAQuery("example.com")
	if err != nil {
		t.Fatal(err)
	}
	response, isAAAA, err := emptyAAAAResponse(query)
	if err != nil || isAAAA || response != nil {
		t.Fatalf("unexpected A-query result: response=%x isAAAA=%v err=%v", response, isAAAA, err)
	}
}

func TestDoHEndpointsIgnoreDuplicatePrimary(t *testing.T) {
	c := newDoHPacketConnWithClient(
		config{DoHFallbackURLs: []string{"https://one.example/dns-query", "https://two.example/dns-query"}},
		nil,
		func(context.Context, string) (net.Conn, error) { return nil, nil },
		"https://one.example/dns-query",
		nil,
		nil,
	)
	defer c.Close()
	if len(c.endpoints) != 2 {
		t.Fatalf("expected two unique endpoints, got %#v", c.endpoints)
	}
}

func TestCustomDoHRootAndQueryEndpointsSendDNSMessages(t *testing.T) {
	for _, suffix := range []string{"", "?key=synthetic", "/dns-query?key=synthetic"} {
		t.Run(suffix, func(t *testing.T) {
			query, _, err := buildAQuery("private-origin.test")
			if err != nil {
				t.Fatal(err)
			}
			wantReply := append([]byte(nil), query...)
			wantReply[2] |= 0x80
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				wantURI := suffix
				if !strings.HasPrefix(wantURI, "/") {
					wantURI = "/" + wantURI
				}
				body, err := io.ReadAll(r.Body)
				if err != nil || r.Method != http.MethodPost || r.RequestURI != wantURI ||
					r.Header.Get("Content-Type") != "application/dns-message" || !bytes.Equal(body, query) {
					t.Error("custom endpoint did not receive the expected DNS POST")
					w.WriteHeader(http.StatusBadRequest)
					return
				}
				w.Header().Set("Content-Type", "application/dns-message")
				_, _ = w.Write(wantReply)
			}))
			defer server.Close()
			endpoint := server.URL + suffix
			if err := validateDoHURL(endpoint); err != nil {
				t.Fatal(err)
			}
			targets := make(chan string, 1)
			connect := func(ctx context.Context, target string) (net.Conn, error) {
				targets <- target
				return (&net.Dialer{}).DialContext(ctx, "tcp", target)
			}
			client := newDoHHTTPClient(connect)
			// Trust the disposable origin certificate, keeping verification enabled.
			client.Transport.(*http.Transport).TLSClientConfig = server.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
			defer client.CloseIdleConnections()
			c := newDoHPacketConnWithClient(config{}, nil, connect, endpoint, client, nil)
			defer c.Close()
			reply, err := c.queryEndpoint(endpoint, query)
			if err != nil || !bytes.Equal(reply, wantReply) {
				t.Fatalf("DNS reply = %x, error = %v", reply, err)
			}
			select {
			case target := <-targets:
				if target != server.Listener.Addr().String() {
					t.Fatalf("unexpected DoH destination %q", target)
				}
			default:
				t.Fatal("DoH did not use the transport's dial callback")
			}
		})
	}
}

func TestDoHEndpointDistinguishesNonDNSResponsesFromHTTPAndTLSErrors(t *testing.T) {
	query, _, err := buildAQuery("private-origin.test")
	if err != nil {
		t.Fatal(err)
	}
	response := append([]byte(nil), query...)
	response[2] |= 0x80
	wrongID := append([]byte(nil), response...)
	wrongID[0] ^= 1
	for _, tc := range []struct {
		name, contentType string
		status            int
		body              []byte
		notDoH            bool
	}{
		{"html", "text/html", 200, []byte("<html>not a resolver</html>"), true},
		{"wrong_mime_suffix", "application/dns-message-json", 200, response, true},
		{"truncated_dns", "application/dns-message", 200, []byte{0, 1}, true},
		{"query_instead_of_reply", "application/dns-message", 200, query, true},
		{"wrong_transaction", "application/dns-message", 200, wrongID, true},
		{"authorized_resolver_required", "text/html", 401, nil, false},
		{"unavailable_resolver", "text/html", 503, nil, false},
		{"dns_with_parameters", "application/dns-message; charset=binary", 200, response, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", tc.contentType)
				w.WriteHeader(tc.status)
				_, _ = w.Write(tc.body)
			}))
			defer server.Close()
			client := server.Client()
			defer client.CloseIdleConnections()
			c := newDoHPacketConnWithClient(config{}, nil, nil, server.URL, client, nil)
			defer c.Close()
			_, err := c.queryEndpoint(server.URL, query)
			if errors.Is(err, errNotDoH) != tc.notDoH {
				t.Fatalf("not_doh = %v, error = %v", tc.notDoH, err)
			}
			if (errorClass(err) == "not_doh") != tc.notDoH {
				t.Fatalf("wrong diagnostic class: %s", errorClass(err))
			}
			if tc.status != 200 && err == nil {
				t.Fatal("HTTP error accepted")
			}
			if tc.status == 200 && !tc.notDoH && err != nil {
				t.Fatal(err)
			}
			// A certificate error must remain a trust failure, not proof of a non-DoH endpoint.
			if tc.name == "html" {
				untrusted := newDoHHTTPClient(func(ctx context.Context, target string) (net.Conn, error) {
					return (&net.Dialer{}).DialContext(ctx, "tcp", target)
				})
				defer untrusted.CloseIdleConnections()
				c.client = untrusted
				_, trustErr := c.queryEndpoint(server.URL, query)
				if errorClass(trustErr) != "certificate" {
					t.Fatalf("TLS error misclassified: %v", trustErr)
				}
			}
		})
	}
	if errorClass(context.DeadlineExceeded) == "not_doh" {
		t.Fatal("timeout mislabeled as non-DoH")
	}
}

type racingDoHTransport func(*http.Request) (*http.Response, error)

func (f racingDoHTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestDoHFastFallbackWinsOnEveryNewFlow(t *testing.T) {
	cancelled := make(chan struct{}, 2)
	client := &http.Client{Transport: racingDoHTransport(func(r *http.Request) (*http.Response, error) {
		if r.URL.Host == "slow.test" {
			<-r.Context().Done()
			cancelled <- struct{}{}
			return nil, r.Context().Err()
		}
		body, _ := io.ReadAll(r.Body)
		body[2] |= 0x80
		return &http.Response{StatusCode: 200, Header: http.Header{"Content-Type": {"application/dns-message"}}, Body: io.NopCloser(bytes.NewReader(body))}, nil
	})}
	query, _, _ := buildAQuery("example.test")
	for range 2 {
		c := newDoHPacketConnWithClient(config{AllowIPv6: true, DoHFallbackURLs: []string{"https://fast.test/dns-query"}}, nil, nil, "https://slow.test/dns-query", client, nil)
		c.SetReadDeadline(time.Now().Add(2 * time.Second))
		if _, err := c.WriteTo(query, nil); err != nil {
			t.Fatal(err)
		}
		body := make([]byte, 512)
		n, _, err := c.ReadFrom(body)
		c.Close()
		if err != nil || !dnsReplyMatches(query, body[:n]) {
			t.Fatalf("reply length=%d err=%v", n, err)
		}
	}
	for range 2 {
		select {
		case <-cancelled:
		case <-time.After(time.Second):
			t.Fatal("losing DoH request was not cancelled")
		}
	}
}
