package mobile

import (
	"context"
	"errors"
	"net"
	"net/http"
	"sync"
	"syscall"
	"time"
)

// The exit's first QUIC flight may contain a 1280-byte datagram before it
// processes our 1280-byte receive limit. Observe the jump's actual path probes
// before encapsulating it; otherwise GOST closes the oversized association.
type jumpMTUPacketConn struct {
	net.PacketConn
	peer  net.Addr
	ready chan struct{}
	once  sync.Once
}

// Preserve DF / path-MTU discovery while observing the packet reader.
func (p *jumpMTUPacketConn) SyscallConn() (syscall.RawConn, error) {
	if socket, ok := p.PacketConn.(syscall.Conn); ok {
		return socket.SyscallConn()
	}
	return nil, errors.New("jump transport is not a UDP socket")
}

func (p *jumpMTUPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	n, peer, err := p.PacketConn.ReadFrom(b)
	if err == nil && peer.String() == p.peer.String() && n >= 1350 {
		p.once.Do(func() { close(p.ready) })
	}
	return n, peer, err
}

func waitForJumpMTU(ctx context.Context, s *masqueSession, host string) error {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	peerReady := false
	for {
		select {
		case <-s.peerMTUReady:
			peerReady = true
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
		if peerReady && s.conn.DatagramPayloadLimit() >= 1290 {
			return nil
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
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
	}
}
