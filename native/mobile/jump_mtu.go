package mobile

import (
	"context"
	"errors"
	"net"
	"net/http"
	"sync/atomic"
	"syscall"
	"time"
)

// Admit browser UDP flights, HTTP/3 / CONNECT-UDP IDs, a browser CID /
// short header, and the peer's 20-byte PMTU convergence interval.
const minimumBrowserDatagramSize = 1350 + 2
const minimumNestedPacketSize = minimumBrowserDatagramSize + (1 + 8 + 4 + 16 + 3) + 20

// GOST's quic-go v0.60.0 conservatively subtracts a 20-byte CID and AEAD tag
// from its discovered packet size for DATAGRAM admission.
const minimumNestedReplyPacketSize = minimumBrowserDatagramSize + (1 + 20 + 16)

// Observe both directions before choosing the nested QUIC packet size.
// SETTINGS alone do not establish a usable UDP path through the jump.
type jumpMTUPacketConn struct {
	net.PacketConn
	peer    net.Addr
	largest atomic.Int64
}

// Preserve DF / path-MTU discovery while observing the packet reader.
type jumpMTUSocket struct{ *jumpMTUPacketConn }

func (p *jumpMTUSocket) SyscallConn() (syscall.RawConn, error) {
	if socket, ok := p.PacketConn.(syscall.Conn); ok {
		return socket.SyscallConn()
	}
	return nil, errors.New("jump transport is not a UDP socket")
}

func (p *jumpMTUPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	n, peer, err := p.PacketConn.ReadFrom(b)
	if err == nil && peer != nil && peer.String() == p.peer.String() && n > 0 && b[0]&0x80 == 0 {
		for old := p.largest.Load(); int64(n) > old; old = p.largest.Load() {
			if p.largest.CompareAndSwap(old, int64(n)) {
				break
			}
		}
	}
	return n, peer, err
}

func waitForMasqueMTU(ctx context.Context, s *masqueSession, host string, packetMTU int) error {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	wasReady := false
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
		// Require a round trip so the peer processes probe ACKs before opening
		// application traffic. Receiving a padded packet alone is insufficient.
		ready := packetMTU > 0 && int(s.peerMTU.largest.Load()) >= max(packetMTU-20, minimumNestedReplyPacketSize)
		if packetMTU == 0 {
			ready = nestedPacketMTU(s, 1) >= minimumNestedPacketSize
		}
		// A bounded, target-free request keeps the connection active while QUIC
		// discovers the path MTU. GOST rejects OPTIONS without opening a target.
		request, err := http.NewRequestWithContext(ctx, http.MethodOptions, "https://"+host+"/", nil)
		if err != nil {
			return err
		}
		response, err := s.client.RoundTrip(request)
		if err != nil {
			return err
		}
		response.Body.Close()
		if ready && wasReady {
			return nil
		}
		wasReady = ready
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
	}
}

// The QUIC send limit already includes its packet and DATAGRAM headers.
// Reserve HTTP/3 quarter-stream ID and UDP context ID in both directions.
// The receive direction uses our immutable source CID length.
func nestedPacketMTU(s *masqueSession, streamIDBytes int) int {
	framing := streamIDBytes + 1
	send := int(s.conn.DatagramPayloadLimit()) - framing
	receive := int(s.peerMTU.largest.Load()) - (1 + s.sourceCIDLength + 4 + 16 + 3) - framing
	return min(send, receive)
}
