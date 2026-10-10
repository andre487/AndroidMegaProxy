package mobile

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	S "github.com/xjasonlyu/tun2socks/v2/transport/socks5"
)

// SOCKS5 carries application bytes without adding encryption or a TLS fingerprint.
var errSOCKS5Auth = errors.New("SOCKS5 authentication rejected")

type socks5Dialer struct {
	lifetime    context.Context
	cancel      context.CancelFunc
	config      config
	protector   Protector
	reporter    Reporter
	stats       *connectionStats
	mu          sync.Mutex
	closed      bool
	sockets     map[*socks5Conn]struct{}
	dohClient   *http.Client
	dohInFlight chan struct{}
}

func (d *socks5Dialer) Close() error {
	d.mu.Lock()
	d.closed = true
	if d.cancel != nil {
		d.cancel()
	}
	sockets := make([]*socks5Conn, 0, len(d.sockets))
	for c := range d.sockets {
		sockets = append(sockets, c)
	}
	client := d.dohClient
	d.mu.Unlock()
	for _, c := range sockets {
		_ = c.Close()
	}
	if client != nil {
		client.CloseIdleConnections()
	}
	return nil
}

func (d *socks5Dialer) track(c net.Conn) (net.Conn, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.closed || len(d.sockets) >= 256 {
		_ = c.Close()
		if d.closed {
			return nil, net.ErrClosed
		}
		return nil, errors.New("SOCKS5 socket limit reached")
	}
	if d.sockets == nil {
		d.sockets = make(map[*socks5Conn]struct{})
	}
	conn := &socks5Conn{Conn: c, owner: d}
	d.sockets[conn] = struct{}{}
	return conn, nil
}

type socks5Conn struct {
	net.Conn
	owner *socks5Dialer
	once  sync.Once
}

func (c *socks5Conn) Close() error {
	err := c.Conn.Close()
	c.once.Do(func() { c.owner.mu.Lock(); delete(c.owner.sockets, c); c.owner.mu.Unlock() })
	return err
}
func (c *socks5Conn) CloseRead() error  { return closeConnRead(c.Conn) }
func (c *socks5Conn) CloseWrite() error { return closeConnWrite(c.Conn) }

func (d *socks5Dialer) checkTarget(target string) error {
	host, port, err := net.SplitHostPort(target)
	if err != nil || host == "" || len(host) > 255 {
		return errors.New("invalid SOCKS5 destination")
	}
	p, err := strconv.Atoi(port)
	if err != nil || p < 1 || p > 65535 {
		return errors.New("invalid SOCKS5 destination port")
	}
	if ip := net.ParseIP(host); ip != nil && ip.To4() == nil && !d.config.AllowIPv6 {
		return errors.New("IPv6 destination blocked by IPv4-only mode")
	}
	return nil
}

func (d *socks5Dialer) protectedDial(ctx context.Context, network, target string) (net.Conn, error) {
	d.mu.Lock()
	if d.closed {
		d.mu.Unlock()
		return nil, net.ErrClosed
	}
	if d.lifetime == nil {
		d.lifetime, d.cancel = context.WithCancel(context.Background())
	}
	lifetime := d.lifetime
	d.mu.Unlock()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	stop := context.AfterFunc(lifetime, cancel)
	defer stop()
	dialer := (&httpsConnectDialer{protector: d.protector}).protectedDialer()
	var c net.Conn
	var err error
	if network == "tcp" && target == d.config.address() {
		c, err = dialMeasuredTCP(ctx, dialer, target, d.stats)
	} else {
		c, err = dialer.DialContext(ctx, network, target)
	}
	if err != nil {
		return nil, err
	}
	return d.track(c)
}

func (d *socks5Dialer) handshake(ctx context.Context, target string, command S.Command) (net.Conn, S.Addr, error) {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	c, err := d.protectedDial(ctx, "tcp", d.config.address())
	if err != nil {
		return nil, nil, err
	}
	success := false
	defer func() {
		if !success {
			_ = c.Close()
		}
	}()
	stop := context.AfterFunc(ctx, func() { _ = c.Close() })
	defer stop()
	if deadline, ok := ctx.Deadline(); ok {
		if err := c.SetDeadline(deadline); err != nil {
			return nil, nil, err
		}
	}
	addr, err := socks5Handshake(c, S.ParseAddrString(target), command, d.config.Username, d.config.Password)
	if ctx.Err() != nil {
		return nil, nil, ctx.Err()
	}
	if err != nil {
		return nil, nil, err
	}
	if err := c.SetDeadline(time.Time{}); err != nil {
		return nil, nil, err
	}
	success = true
	return c, addr, nil
}

// Reuse tun2socks address codecs, while validating every peer-controlled header
// and refusing an authentication-method downgrade when credentials were supplied.
func socks5Handshake(c net.Conn, target S.Addr, command S.Command, username, password string) (S.Addr, error) {
	if target == nil {
		return nil, errors.New("invalid SOCKS5 address")
	}
	method := byte(S.MethodNoAuth)
	if username != "" || password != "" {
		if len(username) < 1 || len(username) > 255 || len(password) < 1 || len(password) > 255 {
			return nil, errors.New("invalid SOCKS5 credentials")
		}
		method = S.MethodUserPass
	}
	if err := writeSOCKS5(c, []byte{5, 1, method}); err != nil {
		return nil, err
	}
	var response [3]byte
	if _, err := io.ReadFull(c, response[:2]); err != nil {
		return nil, err
	}
	if response[0] != 5 || response[1] != method {
		return nil, errSOCKS5Auth
	}
	if method == S.MethodUserPass {
		auth := append([]byte{1, byte(len(username))}, []byte(username)...)
		auth = append(auth, byte(len(password)))
		auth = append(auth, []byte(password)...)
		if err := writeSOCKS5(c, auth); err != nil {
			return nil, err
		}
		if _, err := io.ReadFull(c, response[:2]); err != nil {
			return nil, err
		}
		if response[0] != 1 || response[1] != 0 {
			return nil, errSOCKS5Auth
		}
	}
	if err := writeSOCKS5(c, append([]byte{5, byte(command), 0}, target...)); err != nil {
		return nil, err
	}
	if _, err := io.ReadFull(c, response[:]); err != nil {
		return nil, err
	}
	if response[0] != 5 || response[2] != 0 {
		return nil, errors.New("invalid SOCKS5 response header")
	}
	if response[1] != 0 {
		return nil, fmt.Errorf("SOCKS5 command rejected reply=%d", response[1])
	}
	return S.ReadAddr(c, make([]byte, S.MaxAddrLen))
}
func writeSOCKS5(c net.Conn, b []byte) error {
	n, err := c.Write(b)
	if err == nil && n != len(b) {
		err = io.ErrShortWrite
	}
	return err
}

func (d *socks5Dialer) DialContext(ctx context.Context, m *M.Metadata) (net.Conn, error) {
	return d.connectTarget(ctx, m.DestinationAddress())
}
func (d *socks5Dialer) connectTarget(ctx context.Context, target string) (net.Conn, error) {
	if err := d.checkTarget(target); err != nil {
		return nil, err
	}
	id := nextDiagnosticConnectionID()
	var c net.Conn
	var err error
	if d.config.BypassLocalNetworks && isLocalNetworkTarget(target) {
		c, err = d.protectedDial(ctx, "tcp", target)
	} else {
		c, _, err = d.handshake(ctx, target, S.CmdConnect)
	}
	if err != nil {
		report(d.reporter, "event=connection conn=%d protocol=socks5 stage=socks_handshake result=failed reason=%s", id, errorClass(err))
		return nil, err
	}
	if !(d.config.BypassLocalNetworks && isLocalNetworkTarget(target)) {
		report(d.reporter, "event=connection conn=%d mode=proxy protocol=socks5 stage=tunnel result=established", id)
	}
	return &diagnosticConn{Conn: c, stats: d.stats, reporter: d.reporter, connectionID: id}, nil
}

func (d *socks5Dialer) DialUDP(m *M.Metadata) (net.PacketConn, error) {
	target := m.DestinationAddress()
	if err := d.checkTarget(target); err != nil {
		return nil, err
	}
	if m.DstPort == 53 {
		d.mu.Lock()
		if d.closed {
			d.mu.Unlock()
			return nil, net.ErrClosed
		}
		if d.dohClient == nil {
			d.dohClient = newDoHHTTPClient(d.connectTarget)
			d.dohInFlight = make(chan struct{}, 8)
		}
		client, limiter := d.dohClient, d.dohInFlight
		d.mu.Unlock()
		return newDoHPacketConnWithClient(d.config, d.reporter, d.connectTarget, d.config.DoHURL, client, limiter), nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if d.config.BypassLocalNetworks && isLocalNetworkTarget(target) {
		c, err := d.protectedDial(ctx, "udp", target)
		if err != nil {
			return nil, err
		}
		return &masqueDirectPacketConn{Conn: &diagnosticConn{Conn: c, stats: d.stats, reporter: d.reporter, connectionID: nextDiagnosticConnectionID()}}, nil
	}
	peer := &net.UDPAddr{IP: net.IP(m.DstIP.AsSlice()), Port: int(m.DstPort)}
	return d.openUDP(ctx, target, peer)
}

func (d *socks5Dialer) openUDP(ctx context.Context, target string, peer *net.UDPAddr) (net.PacketConn, error) {
	if err := d.checkTarget(target); err != nil {
		return nil, err
	}
	control, relay, err := d.handshake(ctx, "0.0.0.0:0", S.CmdUDPAssociate)
	if err != nil {
		return nil, err
	}
	success := false
	defer func() {
		if !success {
			_ = control.Close()
		}
	}()
	relayAddr := relay.UDPAddr()
	if relayAddr == nil || relayAddr.Port == 0 {
		return nil, errors.New("SOCKS5 UDP relay must advertise an IP address and nonzero port")
	}
	if relayAddr.IP.IsUnspecified() {
		relayAddr.IP = net.ParseIP(d.config.DialHost)
	}
	// A connected protected socket filters datagrams from anyone except the relay.
	socket, err := d.protectedDial(ctx, "udp", relayAddr.String())
	if err != nil {
		return nil, err
	}
	p := &socks5PacketConn{Conn: socket, control: control, target: S.ParseAddrString(target), peer: peer, stats: d.stats}
	go func() { _, _ = io.Copy(io.Discard, control); _ = p.Close() }()
	report(d.reporter, "event=udp_association protocol=socks5 result=established")
	success = true
	return p, nil
}

type socks5PacketConn struct {
	net.Conn
	control net.Conn
	target  S.Addr
	peer    *net.UDPAddr
	stats   *connectionStats
	once    sync.Once
}

func (p *socks5PacketConn) Close() error {
	var err error
	p.once.Do(func() { _ = p.control.Close(); err = p.Conn.Close() })
	return err
}
func (p *socks5PacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	// SOCKS headers must not reduce the application's receive buffer or expose
	// stale bytes from a preceding datagram to the address decoder.
	packet := make([]byte, 65535)
	for {
		n, err := p.Conn.Read(packet)
		if err != nil {
			return 0, nil, err
		}
		addr, payload, err := S.DecodeUDPPacket(packet[:n])
		if err != nil {
			continue
		}
		// An IP target is pinned to its original IP and port. Domain targets are
		// resolved at the proxy and use a virtual peer for the diagnostic QUIC client.
		if p.target[0] != S.AtypDomainName && addr.String() != p.target.String() {
			continue
		}
		if p.target[0] == S.AtypDomainName && (addr.UDPAddr() == nil || addr.UDPAddr().Port != p.peer.Port) {
			continue
		}
		copied := copy(b, payload)
		if p.stats != nil {
			p.stats.downloadBytes.Add(uint64(copied))
		}
		return copied, p.peer, nil
	}
}
func (p *socks5PacketConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	if addr == nil || addr.String() != p.peer.String() {
		return 0, errors.New("UDP target cannot change")
	}
	packet, err := S.EncodeUDPPacket(p.target, b)
	if err != nil {
		return 0, err
	}
	if len(packet) > 65507 {
		return 0, errors.New("SOCKS5 UDP payload exceeds datagram limit")
	}
	n, err := p.Conn.Write(packet)
	if err != nil {
		return 0, err
	}
	if n != len(packet) {
		return 0, io.ErrShortWrite
	}
	if p.stats != nil {
		p.stats.uploadBytes.Add(uint64(len(b)))
	}
	return len(b), nil
}
