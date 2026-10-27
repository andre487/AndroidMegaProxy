package mobile

import (
	"encoding/binary"

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
	// ponytail: strict-only packet copy, bounded by TUN MTU; use header views if profiling warrants.
	view := pkt.ToView()
	blocked := blockSourceEcho(view.AsSlice())
	view.Release()
	if !blocked {
		d.NetworkDispatcher.DeliverNetworkPacket(protocol, pkt)
	}
}

// Preserve ICMP errors (including Packet Too Big); block Echo, even behind IPv6 extensions.
func blockSourceEcho(p []byte) bool {
	if len(p) == 0 {
		return true
	}
	switch p[0] >> 4 {
	case 4:
		if len(p) < 20 {
			return true
		}
		if p[9] != 1 {
			return false
		}
		offset := int(p[0]&15) * 4
		if offset < 20 || len(p) <= offset {
			return true
		}
		// Fragmented ICMP cannot be attributed safely without a separate reassembly cache.
		if binary.BigEndian.Uint16(p[6:8])&0x3fff != 0 {
			return true
		}
		return p[offset] == 0 || p[offset] == 8
	case 6:
		if len(p) < 40 {
			return true
		}
		next, offset := p[6], 40
		for steps := 0; steps < 16; steps++ {
			switch next {
			case 58:
				return len(p) <= offset || p[offset] == 128 || p[offset] == 129
			case 0, 43, 60: // Hop-by-hop, routing, destination options.
				if len(p) < offset+2 {
					return true
				}
				length := (int(p[offset+1]) + 1) * 8
				if len(p) < offset+length {
					return true
				}
				next, offset = p[offset], offset+length
			case 44:
				if len(p) < offset+8 {
					return true
				}
				fragmentNext := p[offset]
				if binary.BigEndian.Uint16(p[offset+2:offset+4])&0xfff8 != 0 {
					// Non-initial TCP/UDP fragments still undergo validation after reassembly.
					return fragmentNext != 6 && fragmentNext != 17
				}
				if fragmentNext == 58 {
					return true
				}
				next, offset = fragmentNext, offset+8
			case 51: // Authentication header.
				if len(p) < offset+2 {
					return true
				}
				length := (int(p[offset+1]) + 2) * 4
				if len(p) < offset+length {
					return true
				}
				next, offset = p[offset], offset+length
			default:
				return false
			}
		}
		return true
	default:
		return true
	}
}
