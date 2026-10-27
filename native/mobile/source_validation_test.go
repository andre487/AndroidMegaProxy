package mobile

import (
	"net"
	"testing"

	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

type sourceTestValidator struct {
	allowed  bool
	calls    int
	protocol int
}

func (v *sourceTestValidator) Allow(protocol int, source string, sourcePort int, destination string, destinationPort int) bool {
	v.calls++
	v.protocol = protocol
	if source != "10.77.0.1" || sourcePort != 1234 || destination != "192.0.2.1" || destinationPort != 53 {
		panic("incorrect Android socket orientation")
	}
	return v.allowed
}

type sourceTestConn struct {
	adapter.UDPConn
	closed bool
	zero   bool
	events chan struct{}
}

func (c *sourceTestConn) ID() stack.TransportEndpointID {
	return stack.TransportEndpointID{
		RemoteAddress: tcpip.AddrFrom4([4]byte{10, 77, 0, 1}), RemotePort: 1234,
		LocalAddress: tcpip.AddrFrom4([4]byte{192, 0, 2, 1}), LocalPort: 53,
	}
}
func (c *sourceTestConn) Close() error {
	c.closed = true
	if c.events != nil {
		c.events <- struct{}{}
	}
	return nil
}
func (c *sourceTestConn) Read(p []byte) (int, error) {
	if c.zero {
		return 0, nil
	}
	return copy(p, "data"), nil
}
func (c *sourceTestConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, e := c.Read(p)
	return n, nil, e
}

type sourceTestTransport struct {
	tcp, udp int
	conn     adapter.UDPConn
	events   chan struct{}
}

func (h *sourceTestTransport) HandleTCP(c adapter.TCPConn) { h.tcp++; h.events <- struct{}{} }
func (h *sourceTestTransport) HandleUDP(c adapter.UDPConn) {
	h.udp++
	h.conn = c
	h.events <- struct{}{}
}

func TestSourceValidationAdmissionAndUDPPortReuse(t *testing.T) {
	v := &sourceTestValidator{}
	events := make(chan struct{}, 8)
	handler := &sourceTestTransport{events: events}
	guarded := sourceTransport{handler, v, make(chan struct{}, 64)}
	tcp := &sourceTestConn{events: events}
	guarded.HandleTCP(tcp)
	<-events
	if handler.tcp != 0 || !tcp.closed || v.protocol != 6 {
		t.Fatal("denied TCP escaped")
	}
	udp := &sourceTestConn{events: events}
	guarded.HandleUDP(udp)
	<-events
	if handler.udp != 0 || !udp.closed || v.protocol != 17 {
		t.Fatal("denied UDP reached DNS/proxy")
	}
	v.allowed = true
	guarded.HandleTCP(&sourceTestConn{})
	<-events
	if handler.tcp != 1 {
		t.Fatal("allowed TCP lost")
	}
	for _, zero := range []bool{false, true} {
		for _, packet := range []bool{false, true} {
			original := &sourceTestConn{zero: zero}
			guarded.HandleUDP(original)
			<-events
			b := make([]byte, 16)
			read := func() (int, error) {
				if packet {
					n, _, err := handler.conn.ReadFrom(b)
					return n, err
				}
				return handler.conn.Read(b)
			}
			if _, err := read(); err != nil {
				t.Fatal(err)
			}
			v.allowed = false // Same tuple, but Android now reports another/unknown owner.
			if n, err := read(); n != 0 || err == nil || !original.closed {
				t.Fatal("reused UDP source kept cached authorization")
			}
			v.allowed = true
		}
	}
}

func TestSourceEchoPolicy(t *testing.T) {
	v4 := func(kind byte) []byte { p := make([]byte, 28); p[0] = 0x45; p[9] = 1; p[20] = kind; return p }
	v6 := func(kind byte) []byte { p := make([]byte, 48); p[0] = 0x60; p[6] = 58; p[40] = kind; return p }
	for _, tc := range []struct {
		name    string
		p       []byte
		blocked bool
	}{
		{"v4 echo", v4(8), true}, {"v4 reply", v4(0), true}, {"v4 unreachable", v4(3), false},
		{"v6 echo", v6(128), true}, {"v6 reply", v6(129), true}, {"v6 packet too big", v6(2), false},
		{"truncated", []byte{0x60}, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if blockSourceEcho(tc.p) != tc.blocked {
				t.Fatal("wrong ICMP policy")
			}
		})
	}
	p := make([]byte, 56)
	p[0] = 0x60
	p[6] = 60
	p[40] = 58
	p[48] = 128
	if !blockSourceEcho(p) {
		t.Fatal("IPv6 extension bypass")
	}
	p[48] = 2
	if blockSourceEcho(p) {
		t.Fatal("IPv6 errors blocked behind extension")
	}
	fragmented := make([]byte, 56)
	fragmented[0] = 0x60
	fragmented[6] = 44
	fragmented[40] = 17
	if blockSourceEcho(fragmented) {
		t.Fatal("fragmented UDP blocked before source validation")
	}
	fragmented[43] = 8
	if blockSourceEcho(fragmented) {
		t.Fatal("non-initial UDP fragment blocked")
	}
	fragmented[40] = 58
	if !blockSourceEcho(fragmented) {
		t.Fatal("fragmented Echo bypass")
	}
	fragmented[40] = 60
	if !blockSourceEcho(fragmented) {
		t.Fatal("unattributable fragment bypass")
	}

}

func TestDisabledSourceValidationKeepsOriginalDataPath(t *testing.T) {
	endpoint := &sourceLinkEndpoint{}
	handler := &sourceTestTransport{}
	normal := sourceStackConfig(endpoint, handler, nil)
	if normal.LinkEndpoint != endpoint || normal.TransportHandler != handler {
		t.Fatal("disabled mode installed a data-path wrapper")
	}
	strict := sourceStackConfig(endpoint, handler, &sourceTestValidator{})
	if strict.LinkEndpoint == endpoint || strict.TransportHandler == handler {
		t.Fatal("strict mode omitted protection")
	}
}

type sourceBlockingValidator struct{ entered, release chan struct{} }

func (v sourceBlockingValidator) Allow(int, string, int, string, int) bool {
	close(v.entered)
	<-v.release
	return true
}
func TestSourceLookupDoesNotBlockTUNAndAdmissionIsBounded(t *testing.T) {
	events := make(chan struct{}, 2)
	v := sourceBlockingValidator{make(chan struct{}), make(chan struct{})}
	handler := &sourceTestTransport{events: events}
	transport := sourceTransport{handler, v, make(chan struct{}, 1)}
	transport.HandleTCP(&sourceTestConn{}) // Must return while the Android query is blocked.
	<-v.entered
	second := &sourceTestConn{events: events}
	transport.HandleTCP(second)
	<-events
	if !second.closed {
		t.Fatal("unbounded Android lookup queue")
	}
	close(v.release)
	<-events
}
