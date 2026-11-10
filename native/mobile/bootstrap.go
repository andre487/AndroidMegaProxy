package mobile

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"syscall"
	"time"
)

type bootstrapResolver struct {
	name    string
	address string
	url     string
}

var errBootstrapNoAddress = errors.New("bootstrap DNS response contains no requested address")

// Each resolver is dialled by a documented IP address, while TLS still verifies
// the provider hostname. Multiple networks block individual public resolvers, so
// bootstrap must not have a single point of failure.
var bootstrapResolvers = []bootstrapResolver{
	{name: "cloudflare", address: "1.1.1.1:443", url: "https://cloudflare-dns.com/dns-query"},
	{name: "yandex_primary", address: "77.88.8.8:443", url: "https://common.dot.dns.yandex.net/dns-query"},
	{name: "yandex_secondary", address: "77.88.8.1:443", url: "https://common.dot.dns.yandex.net/dns-query"},
	{name: "google", address: "8.8.8.8:443", url: "https://dns.google/dns-query"},
	{name: "quad9", address: "9.9.9.9:443", url: "https://dns.quad9.net/dns-query"},
}

// ResolveProxy bootstraps the proxy address through protected encrypted DNS.
func ResolveProxy(host string, protector Protector, reporter Reporter) (string, error) {
	host = normalizeProxyHost(host)
	if ip := net.ParseIP(host); ip != nil {
		return ip.String(), nil
	}
	if protector == nil {
		return "", errors.New("Android socket protector is required")
	}
	query, id, err := buildAQuery(strings.TrimSuffix(host, "."))
	if err != nil {
		return "", err
	}
	type answer struct{ ip, provider string }
	for _, recordType := range []uint16{1, 28} {
		requestQuery := append([]byte(nil), query...)
		binary.BigEndian.PutUint16(requestQuery[len(requestQuery)-4:], recordType)
		result, resolveErr := raceFirst(context.Background(), len(bootstrapResolvers), func(ctx context.Context, index int) (answer, error) {
			resolver := bootstrapResolvers[index]
			ip, err := resolveProxyWithResolver(ctx, requestQuery, id, resolver, protector)
			if err != nil {
				err = fmt.Errorf("%s: %w", resolver.name, err)
			}
			return answer{ip, resolver.name}, err
		}, nil)
		if resolveErr == nil {
			family := "ipv4"
			if recordType == 28 {
				family = "ipv6"
			}
			report(reporter, "event=bootstrap_dns stage=response result=success family=%s provider=%s", family, result.provider)
			return result.ip, nil
		}
		err = resolveErr
		// Prefer any available A answer; only a valid NODATA response permits AAAA fallback.
		if recordType == 1 && errors.Is(err, errBootstrapNoAddress) {
			continue
		}
		break
	}
	return "", fmt.Errorf("all bootstrap DoH resolvers failed: %w", err)
}

func resolveProxyWithResolver(ctx context.Context, query []byte, id uint16, resolver bootstrapResolver, protector Protector) (string, error) {
	dialer := net.Dialer{
		Timeout: 6 * time.Second,
		Control: func(_, _ string, raw syscall.RawConn) error {
			var protectErr error
			if err := raw.Control(func(fd uintptr) {
				if !protector.Protect(int(fd)) {
					protectErr = errors.New("VpnService.protect rejected bootstrap DoH socket")
				}
			}); err != nil {
				return err
			}
			return protectErr
		},
	}
	transport := &http.Transport{
		Proxy:                  nil,
		TLSHandshakeTimeout:    6 * time.Second,
		MaxResponseHeaderBytes: 64 * 1024,
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return dialer.DialContext(ctx, "tcp", resolver.address)
		},
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 8 * time.Second}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, resolver.url, bytes.NewReader(query))
	if err != nil {
		return "", err
	}
	request.Header.Set("Accept", "application/dns-message")
	request.Header.Set("Content-Type", "application/dns-message")
	response, err := client.Do(request)
	if err != nil {
		return "", fmt.Errorf("bootstrap DoH request: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return "", fmt.Errorf("bootstrap DoH returned %s", response.Status)
	}
	payload, err := io.ReadAll(io.LimitReader(response.Body, 65536))
	if err != nil {
		return "", err
	}
	ip, err := parseAddressResponse(payload, id, binary.BigEndian.Uint16(query[len(query)-4:]))
	if err != nil {
		return "", err
	}
	return ip, nil
}

func buildAQuery(host string) ([]byte, uint16, error) {
	if len(host) == 0 || len(host) > 253 {
		return nil, 0, errors.New("invalid proxy hostname length")
	}
	var idBytes [2]byte
	if _, err := rand.Read(idBytes[:]); err != nil {
		return nil, 0, err
	}
	id := binary.BigEndian.Uint16(idBytes[:])
	message := make([]byte, 12, 256)
	binary.BigEndian.PutUint16(message[0:2], id)
	binary.BigEndian.PutUint16(message[2:4], 0x0100)
	binary.BigEndian.PutUint16(message[4:6], 1)
	for _, label := range bytes.Split([]byte(host), []byte{'.'}) {
		if len(label) == 0 || len(label) > 63 {
			return nil, 0, errors.New("invalid proxy hostname for bootstrap DNS")
		}
		message = append(message, byte(len(label)))
		message = append(message, label...)
	}
	message = append(message, 0, 0, 1, 0, 1)
	return message, id, nil
}

func parseAddressResponse(message []byte, id, requestedType uint16) (string, error) {
	if len(message) < 12 || binary.BigEndian.Uint16(message[0:2]) != id {
		return "", errors.New("invalid bootstrap DNS response")
	}
	if binary.BigEndian.Uint16(message[2:4])&0x000f != 0 {
		return "", errors.New("bootstrap DNS returned an error")
	}
	if binary.BigEndian.Uint16(message[2:4])&0x8200 != 0x8000 {
		return "", errors.New("invalid or truncated bootstrap DNS response")
	}
	questions := int(binary.BigEndian.Uint16(message[4:6]))
	if questions != 1 {
		return "", errors.New("invalid bootstrap DNS question count")
	}
	answers := int(binary.BigEndian.Uint16(message[6:8]))
	offset := 12
	for range questions {
		var err error
		offset, err = skipDNSName(message, offset)
		if err != nil || offset+4 > len(message) {
			return "", errors.New("malformed bootstrap DNS question")
		}
		if binary.BigEndian.Uint16(message[offset:]) != requestedType || binary.BigEndian.Uint16(message[offset+2:]) != 1 {
			return "", errors.New("unexpected bootstrap DNS question")
		}
		offset += 4
	}
	for range answers {
		var err error
		offset, err = skipDNSName(message, offset)
		if err != nil || offset+10 > len(message) {
			return "", errors.New("malformed bootstrap DNS answer")
		}
		recordType := binary.BigEndian.Uint16(message[offset : offset+2])
		recordClass := binary.BigEndian.Uint16(message[offset+2 : offset+4])
		length := int(binary.BigEndian.Uint16(message[offset+8 : offset+10]))
		offset += 10
		if offset+length > len(message) {
			return "", errors.New("truncated bootstrap DNS answer")
		}
		if recordType == requestedType && recordClass == 1 {
			expectedLength := net.IPv4len
			if requestedType == 28 {
				expectedLength = net.IPv6len
			}
			if length != expectedLength {
				return "", errors.New("invalid bootstrap DNS address length")
			}
			return net.IP(message[offset : offset+length]).String(), nil
		}
		offset += length
	}
	return "", errBootstrapNoAddress
}

func skipDNSName(message []byte, offset int) (int, error) {
	for {
		if offset < 0 || offset >= len(message) {
			return 0, io.ErrUnexpectedEOF
		}
		length := int(message[offset])
		offset++
		if length == 0 {
			return offset, nil
		}
		if length&0xc0 == 0xc0 {
			if offset < 0 || offset >= len(message) {
				return 0, io.ErrUnexpectedEOF
			}
			return offset + 1, nil
		}
		if length > 63 || offset+length > len(message) {
			return 0, errors.New("invalid DNS name")
		}
		offset += length
	}
}
