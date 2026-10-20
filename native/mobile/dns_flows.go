package mobile

import (
	"errors"
	"net"
	"sync"
)

const maxDNSFlows = 64

// TCP and UDP share admission before allocating pipes, queues or goroutines.
type dnsFlows struct {
	mu      sync.Mutex
	closed  bool
	packets map[*dohPacketConn]struct{}
}

func (f *dnsFlows) open(create func() *dohPacketConn) (net.PacketConn, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return nil, net.ErrClosed
	}
	if len(f.packets) >= maxDNSFlows {
		return nil, errors.New("DNS flow limit reached")
	}
	if f.packets == nil {
		f.packets = make(map[*dohPacketConn]struct{})
	}
	packet := create()
	packet.onClose = func() {
		f.mu.Lock()
		delete(f.packets, packet)
		f.mu.Unlock()
	}
	f.packets[packet] = struct{}{}
	return packet, nil
}

func (f *dnsFlows) close() {
	f.mu.Lock()
	f.closed = true
	packets := make([]*dohPacketConn, 0, len(f.packets))
	for packet := range f.packets {
		packets = append(packets, packet)
	}
	f.mu.Unlock()
	for _, packet := range packets {
		_ = packet.Close()
	}
}
