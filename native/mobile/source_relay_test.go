package mobile

import (
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/proxy"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"github.com/xjasonlyu/tun2socks/v2/tunnel/statistic"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

type sourceRelayOrigin struct{ net.Conn }

func (c sourceRelayOrigin) ID() stack.TransportEndpointID {
	id := (&sourceTestConn{}).ID()
	id.LocalPort = 443
	return id
}
func (c sourceRelayOrigin) ReadFrom(b []byte) (int, net.Addr, error) {
	n, e := c.Read(b)
	return n, nil, e
}
func (c sourceRelayOrigin) WriteTo(b []byte, _ net.Addr) (int, error) { return c.Write(b) }

type sourceRelayProxy struct {
	proxy.Proxy
	packet net.PacketConn
}

func (p sourceRelayProxy) DialUDP(*M.Metadata) (net.PacketConn, error) { return p.packet, nil }

type sourceRelayReply struct {
	data []byte
	peer net.Addr
}

type sourceRelayPacket struct {
	closed   chan struct{}
	written  chan struct{}
	incoming chan sourceRelayReply
	once     sync.Once
}

func (p *sourceRelayPacket) ReadFrom(b []byte) (int, net.Addr, error) {
	select {
	case <-p.closed:
		return 0, nil, net.ErrClosed
	case reply := <-p.incoming:
		return copy(b, reply.data), reply.peer, nil
	}
}
func (p *sourceRelayPacket) WriteTo(b []byte, _ net.Addr) (int, error) {
	p.written <- struct{}{}
	return len(b), nil
}
func (p *sourceRelayPacket) Close() error { p.once.Do(func() { close(p.closed) }); return nil }
func (p *sourceRelayPacket) LocalAddr() net.Addr {
	return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 1}
}
func (p *sourceRelayPacket) SetDeadline(time.Time) error      { return nil }
func (p *sourceRelayPacket) SetReadDeadline(time.Time) error  { return nil }
func (p *sourceRelayPacket) SetWriteDeadline(time.Time) error { return nil }

func TestSourceDenialAndOriginCloseReleaseIdleUpstreamImmediately(t *testing.T) {
	for _, deny := range []bool{false, true} {
		t.Run(map[bool]string{false: "origin-close", true: "ownership-denied"}[deny], func(t *testing.T) {
			upstream := &sourceRelayPacket{closed: make(chan struct{}), written: make(chan struct{}, 1)}
			defer upstream.Close()
			origin, peer := net.Pipe()
			defer peer.Close()
			var allowed atomic.Bool
			allowed.Store(true)
			guarded := newSourceUDP(sourceRelayOrigin{origin}, sourceValidatorFunc(func(int, string, int, string, int) bool { return allowed.Load() }), 0)
			transport := sourceUDPTransport{dnsTransport{tunnel.New(sourceRelayProxy{packet: upstream}, statistic.DefaultManager)}}
			transport.HandleUDP(guarded)
			if _, err := peer.Write([]byte("first")); err != nil {
				t.Fatal(err)
			}
			select {
			case <-upstream.written:
			case <-time.After(time.Second):
				t.Fatal("first datagram was not relayed")
			}
			if deny {
				allowed.Store(false)
				_, _ = peer.Write([]byte("denied"))
			} else {
				peer.Close()
			}
			select {
			case <-upstream.closed:
			case <-time.After(time.Second):
				t.Fatal("idle upstream retained after source stopped")
			}
			select {
			case <-upstream.written:
				t.Fatal("denied datagram escaped")
			default:
			}
			if deny {
				_ = peer.SetReadDeadline(time.Now().Add(time.Second))
				if _, err := peer.Read(make([]byte, 1)); err != io.EOF {
					t.Fatal("origin remained open", err)
				}
			}
		})
	}
}

func TestSourceRelayPreservesPeerFilteringAndReplies(t *testing.T) {
	upstream := &sourceRelayPacket{closed: make(chan struct{}), written: make(chan struct{}, 1), incoming: make(chan sourceRelayReply, 2)}
	defer upstream.Close()
	origin, peer := net.Pipe()
	defer peer.Close()
	guarded := newSourceUDP(sourceRelayOrigin{origin}, sourceValidatorFunc(func(int, string, int, string, int) bool { return true }), 0)
	transport := sourceUDPTransport{dnsTransport{tunnel.New(sourceRelayProxy{packet: upstream}, statistic.DefaultManager)}}
	transport.HandleUDP(guarded)
	if _, err := peer.Write([]byte("request")); err != nil {
		t.Fatal(err)
	}
	<-upstream.written
	upstream.incoming <- sourceRelayReply{[]byte("wrong-peer"), &net.UDPAddr{IP: net.IPv4(192, 0, 2, 2), Port: 443}}
	upstream.incoming <- sourceRelayReply{[]byte("response"), &net.UDPAddr{IP: net.IPv4(192, 0, 2, 1), Port: 443}}
	_ = peer.SetReadDeadline(time.Now().Add(time.Second))
	b := make([]byte, 32)
	if n, err := peer.Read(b); err != nil || string(b[:n]) != "response" {
		t.Fatal("reply peer validation changed", n, err)
	}
	peer.Close()
	select {
	case <-upstream.closed:
	case <-time.After(time.Second):
		t.Fatal("upstream retained after round trip")
	}
}

type sourceInvalidOrigin struct{ *sourceTestConn }

func (sourceInvalidOrigin) ID() stack.TransportEndpointID { return stack.TransportEndpointID{} }
func TestSourceRelayRejectsInvalidAddressesBeforeDial(t *testing.T) {
	origin := &sourceTestConn{}
	sourceUDPTransport{}.HandleUDP(sourceInvalidOrigin{origin})
	if !origin.closed {
		t.Fatal("invalid source tuple was not closed")
	}
}
