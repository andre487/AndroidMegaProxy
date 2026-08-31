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
	"syscall"
	"time"
)

const bootstrapDoHIP = "1.1.1.1:443"
const bootstrapDoHURL = "https://cloudflare-dns.com/dns-query"

// ResolveProxy bootstraps the proxy address through protected encrypted DNS.
func ResolveProxy(host string, protector Protector, reporter Reporter) (string, error) {
	query, id, err := buildAQuery(host)
	if err != nil {
		return "", err
	}
	dialer := net.Dialer{
		Timeout: 10 * time.Second,
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
		Proxy:               nil,
		TLSHandshakeTimeout: 10 * time.Second,
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			return dialer.DialContext(ctx, "tcp", bootstrapDoHIP)
		},
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 15 * time.Second}
	request, err := http.NewRequest(http.MethodPost, bootstrapDoHURL, bytes.NewReader(query))
	if err != nil {
		return "", err
	}
	request.Header.Set("Accept", "application/dns-message")
	request.Header.Set("Content-Type", "application/dns-message")
	report(reporter, "Proxy bootstrap DNS: protected DoH request for %s via %s", host, bootstrapDoHIP)
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
	ip, err := parseAResponse(payload, id)
	if err != nil {
		return "", err
	}
	report(reporter, "Proxy bootstrap DNS: %s -> %s", host, ip)
	return ip, nil
}

func buildAQuery(host string) ([]byte, uint16, error) {
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

func parseAResponse(message []byte, id uint16) (string, error) {
	if len(message) < 12 || binary.BigEndian.Uint16(message[0:2]) != id {
		return "", errors.New("invalid bootstrap DNS response")
	}
	if binary.BigEndian.Uint16(message[2:4])&0x000f != 0 {
		return "", errors.New("bootstrap DNS returned an error")
	}
	questions := int(binary.BigEndian.Uint16(message[4:6]))
	answers := int(binary.BigEndian.Uint16(message[6:8]))
	offset := 12
	for range questions {
		var err error
		offset, err = skipDNSName(message, offset)
		if err != nil || offset+4 > len(message) {
			return "", errors.New("malformed bootstrap DNS question")
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
		if recordType == 1 && recordClass == 1 && length == net.IPv4len {
			return net.IP(message[offset : offset+length]).String(), nil
		}
		offset += length
	}
	return "", errors.New("bootstrap DNS response contains no IPv4 address")
}

func skipDNSName(message []byte, offset int) (int, error) {
	for {
		if offset >= len(message) {
			return 0, io.ErrUnexpectedEOF
		}
		length := int(message[offset])
		offset++
		if length == 0 {
			return offset, nil
		}
		if length&0xc0 == 0xc0 {
			if offset >= len(message) {
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
