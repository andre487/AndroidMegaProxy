package mobile

import (
	"fmt"
	"net"
	"testing"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// These fixtures deliberately exclude Android JNI, owner lookup and real I/O.
// The same payload copy and interface calls are included in all UDP modes.
type benchmarkSourceConn struct {
	sourceTestConn
	payload []byte
}

func (c *benchmarkSourceConn) Read(p []byte) (int, error) { return copy(p, c.payload), nil }
func (c *benchmarkSourceConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, err := c.Read(p)
	return n, nil, err
}

func BenchmarkCoreUDPRead(b *testing.B) {
	for _, size := range []int{64, 1400, 65507} {
		for _, method := range []string{"Read", "ReadFrom"} {
			for _, mode := range []string{"disabled", "standard", "advanced"} {
				b.Run(fmt.Sprintf("bytes=%d/method=%s/mode=%s", size, method, mode), func(b *testing.B) {
					original := &benchmarkSourceConn{payload: make([]byte, size)}
					var conn adapter.UDPConn = original
					validator := &sourceTestValidator{allowed: true}
					if mode != "disabled" {
						interval := time.Duration(0)
						if mode == "standard" {
							interval = time.Second
						}
						guarded := newSourceUDP(original, limitedSourceValidator{validator, make(chan struct{}, 8)}, interval)
						if !guarded.query() {
							b.Fatal("initial admission failed")
						}
						guarded.recheckAt = time.Now().Add(interval)
						conn = guarded
					}
					validator.calls = 0
					payload := make([]byte, size)
					b.SetBytes(int64(size))
					b.ReportAllocs()
					for b.Loop() {
						var n int
						var err error
						if method == "Read" {
							n, err = conn.Read(payload)
						} else {
							n, _, err = conn.ReadFrom(payload)
						}
						if n != size || err != nil {
							b.Fatalf("read: n=%d err=%v", n, err)
						}
					}
					b.ReportMetric(float64(validator.calls)/float64(b.N), "owner_queries/op")
					if mode == "advanced" && validator.calls != b.N || mode == "disabled" && validator.calls != 0 {
						b.Fatal("incorrect ownership query count")
					}
				})
			}
		}
	}
}

// Exercise cache expiry deterministically, without a one-second sleep per sample.
func BenchmarkCoreUDPRecheck(b *testing.B) {
	for _, expired := range []bool{false, true} {
		b.Run(fmt.Sprintf("expired=%t", expired), func(b *testing.B) {
			validator := &sourceTestValidator{allowed: true}
			conn := newSourceUDP(&sourceTestConn{}, limitedSourceValidator{validator, make(chan struct{}, 8)}, time.Second)
			now := time.Unix(1, 0)
			conn.recheckAt = now.Add(time.Second)
			b.ReportAllocs()
			for b.Loop() {
				if expired {
					now = now.Add(time.Second)
				}
				if !conn.allowedAt(now) {
					b.Fatal("unexpected denial")
				}
			}
			b.ReportMetric(float64(validator.calls)/float64(b.N), "owner_queries/op")
			if expired && validator.calls != b.N || !expired && validator.calls != 0 {
				b.Fatal("incorrect ownership query count")
			}
		})
	}
}

// Includes dispatch, goroutine scheduling, address formatting and the query budget.
// No sockets, dialers or proxy handshakes are created here.
func BenchmarkCoreAdmission(b *testing.B) {
	for _, protocol := range []string{"TCP", "UDP"} {
		for _, mode := range []string{"disabled", "standard", "advanced"} {
			b.Run(fmt.Sprintf("protocol=%s/mode=%s", protocol, mode), func(b *testing.B) {
				events := make(chan struct{}, 1)
				handler := &sourceTestTransport{events: events}
				var transport adapter.TransportHandler = handler
				validator := &sourceTestValidator{allowed: true}
				if mode != "disabled" {
					interval := time.Duration(0)
					if mode == "standard" {
						interval = time.Second
					}
					transport = sourceTransport{handler, limitedSourceValidator{validator, make(chan struct{}, 8)}, make(chan struct{}, 64), interval}
				}
				conn := &sourceTestConn{}
				b.ReportAllocs()
				for b.Loop() {
					if protocol == "TCP" {
						transport.HandleTCP(conn)
					} else {
						transport.HandleUDP(conn)
					}
					<-events
				}
				b.ReportMetric(float64(validator.calls)/float64(b.N), "owner_queries/op")
				if handler.tcp+handler.udp != b.N || mode != "disabled" && validator.calls != b.N {
					b.Fatal("admissions were skipped")
				}
			})
		}
	}
}

// Both strict levels install the same packet filter. Keep the historical
// full-copy comparison in BenchmarkSourcePacketFilter separate from this suite.
func BenchmarkCorePacketFilter(b *testing.B) {
	for _, size := range []int{64, 1400, 65535} {
		for _, ipv6 := range []bool{false, true} {
			for _, strict := range []bool{false, true} {
				b.Run(fmt.Sprintf("bytes=%d/ipv6=%t/strict=%t", size, ipv6, strict), func(b *testing.B) {
					p := make([]byte, size)
					protocol := tcpip.NetworkProtocolNumber(0x800)
					p[0], p[9] = 0x45, 6
					if ipv6 {
						p[0], p[6] = 0x60, 17
						protocol = 0x86dd
					}
					pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(p)})
					defer pkt.DecRef()
					receiver := &sourceCountingDispatcher{}
					var dispatcher stack.NetworkDispatcher = receiver
					if strict {
						dispatcher = sourceDispatcher{receiver}
					}
					b.SetBytes(int64(size))
					b.ReportAllocs()
					for b.Loop() {
						dispatcher.DeliverNetworkPacket(protocol, pkt)
					}
					if receiver.packets != b.N {
						b.Fatal("packets were dropped")
					}
				})
			}
		}
	}
}
