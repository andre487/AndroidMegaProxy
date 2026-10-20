package mobile

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// The legacy dohUrl field stores explicit tcp:// or udp:// endpoints too.
// Old clients reject these instead of silently choosing a different resolver.
func plainDNSTarget(endpoint string) (string, string, error) {
	u, err := url.Parse(endpoint)
	if err != nil || (u.Scheme != "tcp" && u.Scheme != "udp") || u.User != nil ||
		strings.Contains(endpoint, "#") || u.RawQuery != "" || u.ForceQuery || u.Path != "" {
		return "", "", errors.New("invalid plain DNS endpoint")
	}
	ip, err := netip.ParseAddr(u.Hostname())
	if err != nil || (ip.Is6() && !strings.HasPrefix(u.Host, "[")) {
		return "", "", errors.New("DNS server must be an IP address")
	}
	port := u.Port()
	if port == "" {
		port = "53"
	}
	n, err := strconv.Atoi(port)
	if err != nil || n < 1 || n > 65535 {
		return "", "", errors.New("invalid DNS server port")
	}
	return u.Scheme, net.JoinHostPort(ip.String(), port), nil
}

func (c config) plainDNS() bool {
	u, err := url.Parse(c.DoHURL)
	return err == nil && (u.Scheme == "tcp" || u.Scheme == "udp")
}

// Explicit DNS resolvers use the proxy even for private IPs.
func (c config) bypassTarget(target string) bool {
	if c.plainDNS() {
		_, server, err := plainDNSTarget(c.DoHURL)
		if err == nil && server == target {
			return false
		}
	}
	for _, endpoint := range append([]string{c.DoHURL}, c.DoHFallbackURLs...) {
		u, err := url.Parse(endpoint)
		if err == nil && strings.EqualFold(u.Scheme, "https") {
			port := u.Port()
			if port == "" {
				port = "443"
			}
			if net.JoinHostPort(u.Hostname(), port) == target {
				return false
			}
		}
	}
	return c.BypassLocalNetworks && isLocalNetworkTarget(target)
}

func dnsReplyMatches(query, reply []byte) bool {
	return len(query) >= 12 && len(reply) >= 12 && len(reply) <= 65535 && reply[2]&0x80 != 0 &&
		binary.BigEndian.Uint16(query) == binary.BigEndian.Uint16(reply)
}

func (c *dohPacketConn) queryPlainDNS(ctx context.Context, endpoint string, query []byte) ([]byte, error) {
	if !c.config.AllowInsecureDNS {
		return nil, errors.New("insecure DNS requires local approval")
	}
	mode, target, err := plainDNSTarget(endpoint)
	if err != nil {
		return nil, err
	}
	host, _, _ := net.SplitHostPort(target)
	if !c.config.AllowIPv6 && net.ParseIP(host).To4() == nil {
		return nil, errors.New("IPv6 destination blocked by IPv4-only mode")
	}
	if mode == "tcp" {
		conn, err := c.connect(ctx, target)
		if err != nil {
			return nil, err
		}
		defer conn.Close()
		cancel := context.AfterFunc(ctx, func() { _ = conn.Close() })
		defer cancel()
		if deadline, ok := ctx.Deadline(); ok {
			_ = conn.SetDeadline(deadline)
		}
		frame := make([]byte, len(query)+2)
		binary.BigEndian.PutUint16(frame, uint16(len(query)))
		copy(frame[2:], query)
		if _, err = io.Copy(conn, bytes.NewReader(frame)); err != nil {
			return nil, err
		}
		var header [2]byte
		if _, err = io.ReadFull(conn, header[:]); err != nil {
			return nil, err
		}
		size := int(binary.BigEndian.Uint16(header[:]))
		if size < 12 {
			return nil, errors.New("invalid TCP DNS response size")
		}
		reply := make([]byte, size)
		if _, err = io.ReadFull(conn, reply); err != nil {
			return nil, err
		}
		if !dnsReplyMatches(query, reply) {
			return nil, errors.New("invalid DNS response")
		}
		return reply, nil
	}
	if c.connectUDP == nil {
		return nil, errors.New("UDP DNS transport unavailable")
	}
	conn, err := c.connectUDP(ctx, target)
	if err != nil {
		return nil, err
	} // Never retry a failed tunneled query on the direct network.
	defer conn.Close()
	cancel := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer cancel()
	deadline := time.Now().Add(3 * time.Second)
	if until, ok := ctx.Deadline(); ok {
		deadline = until
	}
	_ = conn.SetDeadline(deadline)
	peer, err := net.ResolveUDPAddr("udp", target) // Target is already a numeric IP.
	if err != nil {
		return nil, err
	}
	if _, err = conn.WriteTo(query, peer); err != nil {
		return nil, err
	}
	reply := make([]byte, 65536)
	n, _, err := conn.ReadFrom(reply)
	if err != nil {
		return nil, err
	}
	if !dnsReplyMatches(query, reply[:n]) {
		return nil, errors.New("invalid DNS response")
	}
	return reply[:n], nil
}

func directDNSUDP(ctx context.Context, target string, owner *httpsConnectDialer, protector Protector) (net.PacketConn, error) {
	dialer := (&httpsConnectDialer{protector: protector}).protectedDialer()
	conn, err := owner.dialDirect(ctx, dialer, "udp", target)
	if err != nil {
		return nil, err
	}
	return &masqueDirectPacketConn{Conn: conn}, nil
}
