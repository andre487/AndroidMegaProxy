package mobile

import (
	"errors"
	"io"
	"net"
	"sync"
)

const maxDNSFlows = 64

// TCP and UDP share admission before allocating pipes, queues or goroutines.
type dnsFlows struct {
	mu       sync.Mutex
	closed   bool
	sequence uint64
	packets  map[*dnsFlow]struct{}
}

type dnsFlow struct {
	*dohPacketConn
	owner     *dnsFlows
	udp       bool
	pending   int
	completed bool
	lastUsed  uint64
	origin    io.Closer
}

func (f *dnsFlows) open(udp bool, create func() *dohPacketConn) (net.PacketConn, error) {
	f.mu.Lock()
	if f.closed {
		f.mu.Unlock()
		return nil, net.ErrClosed
	}
	var evicted *dnsFlow
	if len(f.packets) >= maxDNSFlows {
		// ponytail: scan at most 64 flows; a separate LRU is unnecessary at this bound.
		for packet := range f.packets {
			if packet.udp && packet.completed && packet.pending == 0 && (evicted == nil || packet.lastUsed < evicted.lastUsed) {
				evicted = packet
			}
		}
		if evicted == nil {
			f.mu.Unlock()
			return nil, errors.New("DNS flow limit reached")
		}
		delete(f.packets, evicted)
	}
	if f.packets == nil {
		f.packets = make(map[*dnsFlow]struct{})
	}
	packet := &dnsFlow{dohPacketConn: create(), owner: f, udp: udp}
	packet.onClose = func() {
		f.mu.Lock()
		delete(f.packets, packet)
		origin := packet.origin
		f.mu.Unlock()
		if origin != nil {
			_ = origin.Close()
		}
	}
	f.packets[packet] = struct{}{}
	f.mu.Unlock()
	if evicted != nil {
		_ = evicted.Close()
	}
	return packet, nil
}

func (p *dnsFlow) bindOrigin(origin io.Closer) {
	p.owner.mu.Lock()
	_, open := p.owner.packets[p]
	if open {
		p.origin = origin
	}
	p.owner.mu.Unlock()
	if !open {
		_ = origin.Close()
	}
}

func (p *dnsFlow) WriteTo(b []byte, addr net.Addr) (int, error) {
	p.owner.mu.Lock()
	if _, open := p.owner.packets[p]; !open {
		p.owner.mu.Unlock()
		return 0, net.ErrClosed
	}
	p.pending++
	p.owner.mu.Unlock()
	n, err := p.dohPacketConn.WriteTo(b, addr)
	if err != nil {
		p.complete(false)
	}
	return n, err
}

func (p *dnsFlow) ReadFrom(b []byte) (int, net.Addr, error) {
	n, addr, err := p.dohPacketConn.ReadFrom(b)
	p.owner.mu.Lock()
	direct := p.origin == nil
	p.owner.mu.Unlock()
	// The TUN relay completes only after delivering the response to the origin.
	if err == nil && direct {
		p.complete(true)
	}
	return n, addr, err
}

func (p *dnsFlow) complete(answered bool) {
	p.owner.mu.Lock()
	defer p.owner.mu.Unlock()
	if p.pending > 0 {
		p.pending--
	}
	p.completed = p.completed || answered
	p.owner.sequence++
	p.lastUsed = p.owner.sequence
}

func (f *dnsFlows) close() {
	f.mu.Lock()
	f.closed = true
	packets := make([]*dnsFlow, 0, len(f.packets))
	for packet := range f.packets {
		packets = append(packets, packet)
	}
	f.mu.Unlock()
	for _, packet := range packets {
		_ = packet.Close()
	}
}
