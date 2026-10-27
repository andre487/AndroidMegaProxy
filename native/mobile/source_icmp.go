package mobile

import (
	"encoding/binary"
	"gvisor.dev/gvisor/pkg/buffer"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// Only installed for strict sessions, before gVisor can synthesize Echo Replies.
type sourceLinkEndpoint struct{ stack.LinkEndpoint }

func (e *sourceLinkEndpoint) Attach(dispatcher stack.NetworkDispatcher) {
	if dispatcher == nil {
		e.LinkEndpoint.Attach(nil)
		return
	}
	e.LinkEndpoint.Attach(sourceDispatcher{dispatcher})
}

type sourceDispatcher struct{ stack.NetworkDispatcher }

func (d sourceDispatcher) DeliverNetworkPacket(protocol tcpip.NetworkProtocolNumber, pkt *stack.PacketBuffer) {
	data := pkt.Data()
	var copied *buffer.View
	blocked := blockSourceEchoPrefix(func(n int) ([]byte, bool) {
		if copied == nil && n <= 128 {
			return data.PullUp(n)
		}
		// ponytail: long extension chains use one MTU-sized copy, avoiding repeated prefix coalescing.
		if copied == nil {
			copied = pkt.ToView()
		}
		p := copied.AsSlice()
		if n > len(p) {
			return nil, false
		}
		return p[:n], true
	})
	if copied != nil {
		copied.Release()
	}
	if !blocked {
		d.NetworkDispatcher.DeliverNetworkPacket(protocol, pkt)
	}
}

// Preserve ICMP errors (including Packet Too Big); block Echo, even behind IPv6 extensions.
func blockSourceEchoPrefix(prefix func(int) ([]byte, bool)) bool {
	p, ok := prefix(1)
	if !ok {
		return true
	}
	switch p[0] >> 4 {
	case 4:
		p, ok = prefix(20)
		if !ok {
			return true
		}
		if p[9] != 1 {
			return false
		}
		offset := int(p[0]&15) * 4
		if offset < 20 {
			return true
		}
		if binary.BigEndian.Uint16(p[6:8])&0x3fff != 0 {
			return true
		}
		p, ok = prefix(offset + 1)
		return !ok || p[offset] == 0 || p[offset] == 8
	case 6:
		p, ok = prefix(40)
		if !ok {
			return true
		}
		next, offset := p[6], 40
		for steps := 0; steps < 16; steps++ {
			switch next {
			case 58:
				p, ok = prefix(offset + 1)
				return !ok || p[offset] == 128 || p[offset] == 129
			case 0, 43, 60, 51:
				p, ok = prefix(offset + 2)
				if !ok {
					return true
				}
				length := (int(p[offset+1]) + 1) * 8
				if next == 51 {
					length = (int(p[offset+1]) + 2) * 4
				}
				p, ok = prefix(offset + length)
				if !ok {
					return true
				}
				next, offset = p[offset], offset+length
			case 44:
				p, ok = prefix(offset + 8)
				if !ok {
					return true
				}
				fragmentNext := p[offset]
				if binary.BigEndian.Uint16(p[offset+2:offset+4])&0xfff8 != 0 {
					return fragmentNext != 6 && fragmentNext != 17
				}
				if fragmentNext == 58 {
					return true
				}
				next, offset = fragmentNext, offset+8
			default:
				return false
			}
		}
		return true
	default:
		return true
	}
}
