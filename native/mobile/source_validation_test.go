package mobile

import (
	"fmt"
	"gvisor.dev/gvisor/pkg/buffer"
	"net"
	"sync/atomic"
	"testing"
	"time"

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
	guarded := sourceTransport{handler, v, make(chan struct{}, 64), 0}
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
	handler := dnsTransport{}
	normal := sourceStackConfig(endpoint, handler, nil, 0)
	if normal.LinkEndpoint != endpoint || normal.TransportHandler != handler {
		t.Fatal("disabled mode installed a data-path wrapper")
	}
	strict := sourceStackConfig(endpoint, handler, &sourceTestValidator{}, 0)
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
	transport := sourceTransport{handler, v, make(chan struct{}, 1), 0}
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

func TestStandardUDPValidationWindowIsFixedAndFlowLocal(t *testing.T) {
	v := &sourceTestValidator{allowed: true}
	now := time.Unix(100, 0)
	conn := newSourceUDP(&sourceTestConn{}, v, time.Second)
	if !conn.allowedAt(now) || v.calls != 1 {
		t.Fatal("new flow was not checked")
	}
	v.allowed = false
	if !conn.allowedAt(now.Add(900*time.Millisecond)) || v.calls != 1 {
		t.Fatal("standard mode did not cache admission")
	}
	if !conn.allowedAt(now.Add(999 * time.Millisecond)) {
		t.Fatal("cache expired early")
	}
	if conn.allowedAt(now.Add(time.Second)) || v.calls != 2 {
		t.Fatal("cache hit extended the stale-owner window")
	}
	another := newSourceUDP(&sourceTestConn{}, v, time.Second)
	if another.allowedAt(now) || v.calls != 3 {
		t.Fatal("new flow reused another flow's decision")
	}
	v.allowed = true
	if conn.allowedAt(now.Add(2*time.Second)) || v.calls != 3 {
		t.Fatal("denied flow resurrected cached permission")
	}
	fresh := newSourceUDP(&sourceTestConn{}, v, time.Second)
	if !fresh.allowedAt(now.Add(2*time.Second)) || v.calls != 4 {
		t.Fatal("fresh socket not rechecked")
	}
}

func TestSourceValidationIntervalRejectsUnsupportedModes(t *testing.T) {
	for _, interval := range []int{-1, 1, 1001, 1 << 30} {
		if err := StartStrict(-1, 1400, "", nil, nil, &sourceTestValidator{}, interval); err == nil || err.Error() != "unsupported UDP source validation interval" {
			t.Fatal("unbounded source cache interval")
		}
	}
}

func blockSourceEcho(p []byte) bool {
	return blockSourceEchoPrefix(func(n int) ([]byte, bool) {
		if n > len(p) {
			return nil, false
		}
		return p[:n], true
	})
}

type sourceCountingDispatcher struct{ packets int }

func (d *sourceCountingDispatcher) DeliverNetworkPacket(tcpip.NetworkProtocolNumber, *stack.PacketBuffer) {
	d.packets++
}
func (d *sourceCountingDispatcher) DeliverLinkPacket(tcpip.NetworkProtocolNumber, *stack.PacketBuffer) {
}

func TestSourceFilterReadsHeadersOnlyAndHandlesTruncation(t *testing.T) {
	for _, v6 := range []bool{false, true} {
		p := make([]byte, 65535)
		headerSize := 20
		if v6 {
			p[0] = 0x60
			p[6] = 17
			headerSize = 40
		} else {
			p[0] = 0x45
			p[9] = 6
		}
		requested := 0
		blocked := blockSourceEchoPrefix(func(n int) ([]byte, bool) {
			if n > requested {
				requested = n
			}
			return p[:n], true
		})
		if blocked || requested != headerSize {
			t.Fatal("payload was read/copied for a TCP/UDP packet")
		}
	}
	// Every truncation of a non-empty extension chain must be safe to inspect.
	p := make([]byte, 56)
	p[0] = 0x60
	p[6] = 60
	p[40] = 58
	p[48] = 128
	for n := 0; n <= len(p); n++ {
		_ = blockSourceEcho(p[:n])
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(p[:n])})
		receiver := &sourceCountingDispatcher{}
		sourceDispatcher{receiver}.DeliverNetworkPacket(0x86dd, pkt)
		pkt.DecRef()
		if receiver.packets != 0 {
			t.Fatalf("truncated/Echo packet escaped: %d bytes", n)
		}
	}
}

func BenchmarkSourcePacketFilter(b *testing.B) {
	for _, size := range []int{1500, 65535} {
		p := make([]byte, size)
		p[0] = 0x45
		p[9] = 6
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(p)})
		defer pkt.DecRef()
		for _, mode := range []string{"disabled", "header-only", "previous-full-copy"} {
			b.Run(fmt.Sprintf("%d/%s", size, mode), func(b *testing.B) {
				receiver := &sourceCountingDispatcher{}
				strict := sourceDispatcher{receiver}
				b.ReportAllocs()
				for b.Loop() {
					switch mode {
					case "disabled":
						receiver.DeliverNetworkPacket(0x800, pkt)
					case "header-only":
						strict.DeliverNetworkPacket(0x800, pkt)
					case "previous-full-copy":
						v := pkt.ToView()
						blocked := blockSourceEcho(v.AsSlice())
						v.Release()
						if !blocked {
							receiver.DeliverNetworkPacket(0x800, pkt)
						}
					}
				}
			})
		}
	}
}

type sourceValidatorFunc func(int, string, int, string, int) bool

func (f sourceValidatorFunc) Allow(p int, s string, sp int, d string, dp int) bool {
	return f(p, s, sp, d, dp)
}
func TestSourceQueriesAreBoundedAcrossUDPAndAdmissions(t *testing.T) {
	entered := make(chan struct{}, 2)
	release := make(chan struct{})
	var calls atomic.Int32
	validator := limitedSourceValidator{sourceValidatorFunc(func(int, string, int, string, int) bool { calls.Add(1); entered <- struct{}{}; <-release; return true }), make(chan struct{}, 2)}
	done := make(chan struct{}, 2)
	for i := 0; i < 2; i++ {
		go func() { validator.Allow(17, "", 1, "", 2); done <- struct{}{} }()
	}
	<-entered
	<-entered
	if validator.Allow(6, "", 1, "", 2) || calls.Load() != 2 {
		t.Fatal("excess query attached another JNI thread")
	}
	close(release)
	<-done
	<-done
	if !validator.Allow(17, "", 1, "", 2) {
		t.Fatal("query slot leaked")
	}
}

func TestSourceUDPReusesNumericAddressesWithoutGoAllocations(t *testing.T) {
	v := &sourceTestValidator{allowed: true}
	conn := newSourceUDP(&sourceTestConn{}, v, 0)
	allocations := testing.AllocsPerRun(1000, func() {
		if !conn.query() {
			panic("unexpected denial")
		}
	})
	if allocations != 0 {
		t.Fatalf("UDP reformats addresses on every query: %v allocations", allocations)
	}
}

func TestSourceQueryBudgetSurvivesSessionReplacement(t *testing.T) {
	endpoint := &sourceLinkEndpoint{}
	handler := dnsTransport{}
	first := sourceStackConfig(endpoint, handler, &sourceTestValidator{}, 0).TransportHandler.(sourceTransport).validator.(limitedSourceValidator)
	second := sourceStackConfig(endpoint, handler, &sourceTestValidator{}, time.Second).TransportHandler.(sourceTransport).validator.(limitedSourceValidator)
	if first.slots != second.slots || cap(first.slots) != 8 {
		t.Fatal("reconnect reset the JNI concurrency budget")
	}
}

func TestSourceFilterHandlesSegmentedLongExtensionChains(t *testing.T) {
	p := make([]byte, 40+8*128+8)
	p[0] = 0x60
	p[6] = 60
	for offset := 40; offset < 40+8*128; offset += 128 {
		p[offset] = 60
		p[offset+1] = 15
	}
	p[40+7*128] = 17
	payload := buffer.MakeWithData(p[:64])
	for offset := 64; offset < len(p); offset += 64 {
		part := buffer.MakeWithData(p[offset:min(offset+64, len(p))])
		payload.Merge(&part)
	}
	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: payload})
	defer pkt.DecRef()
	receiver := &sourceCountingDispatcher{}
	sourceDispatcher{receiver}.DeliverNetworkPacket(0x86dd, pkt)
	if receiver.packets != 1 || pkt.Data().Size() != len(p) {
		t.Fatal("long headers changed or blocked a UDP packet")
	}
}
