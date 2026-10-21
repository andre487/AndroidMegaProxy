package mobile

import (
	"net"
	"net/netip"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/buffer"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"github.com/xjasonlyu/tun2socks/v2/tunnel/statistic"
)

// DNS eviction must close both relay directions, including the idle TUN endpoint.
// The upstream UDP pipe otherwise waits for the idle origin until its timeout.
// Other TCP/UDP traffic keeps the upstream tunnel's forwarding behavior.
type dnsTransport struct{ *tunnel.Tunnel }

func (t dnsTransport) HandleUDP(origin adapter.UDPConn) {
	id := origin.ID()
	if id.LocalPort != 53 {
		t.Tunnel.HandleUDP(origin)
		return
	}
	source, _ := netip.AddrFromSlice(id.RemoteAddress.AsSlice())
	target, _ := netip.AddrFromSlice(id.LocalAddress.AsSlice())
	metadata := &M.Metadata{Network: M.UDP, SrcIP: source, SrcPort: id.RemotePort, DstIP: target, DstPort: id.LocalPort}
	packet, err := t.Proxy().DialUDP(metadata)
	if err != nil {
		_ = origin.Close()
		return
	}
	flow, ok := packet.(*dnsFlow)
	if !ok {
		_ = packet.Close()
		_ = origin.Close()
		return
	}
	flow.bindOrigin(origin)
	remote := statistic.NewUDPTracker(packet, metadata, statistic.DefaultManager)
	peer := metadata.UDPAddr()
	// Admission above bounds the buffers and goroutines even during DNS floods.
	go func() {
		defer remote.Close()
		defer origin.Close()
		go func() {
			defer remote.Close()
			defer origin.Close()
			relayDNSPackets(remote, origin, peer, nil)
		}()
		relayDNSPackets(origin, remote, nil, func() { flow.complete(true) })
	}()
}

func relayDNSPackets(dst, src net.PacketConn, peer net.Addr, delivered func()) {
	buf := buffer.Get(buffer.MaxSegmentSize)
	defer buffer.Put(buf)
	for {
		_ = src.SetReadDeadline(time.Now().Add(60 * time.Second))
		n, _, err := src.ReadFrom(buf)
		if err != nil {
			return
		}
		if _, err = dst.WriteTo(buf[:n], peer); err != nil {
			return
		}
		if delivered != nil {
			delivered()
		}
		_ = dst.SetReadDeadline(time.Now().Add(60 * time.Second))
	}
}
