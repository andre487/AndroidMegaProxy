package mobile

import (
	"net"
	"net/netip"
	"sync"

	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/tunnel/statistic"
)

// Only strict sessions use this handler. Stock UDP forwarding waits for both
// copy loops before closing upstream; a source denial must close it immediately.
type sourceUDPTransport struct{ dnsTransport }

func (t sourceUDPTransport) HandleUDP(origin adapter.UDPConn) {
	id := origin.ID()
	if id.LocalPort == 53 {
		t.dnsTransport.HandleUDP(origin)
		return
	}
	source, _ := netip.AddrFromSlice(id.RemoteAddress.AsSlice())
	target, _ := netip.AddrFromSlice(id.LocalAddress.AsSlice())
	if !source.IsValid() || !target.IsValid() {
		_ = origin.Close()
		return
	}
	metadata := &M.Metadata{Network: M.UDP, SrcIP: source, SrcPort: id.RemotePort, DstIP: target, DstPort: id.LocalPort}
	packet, err := t.Proxy().DialUDP(metadata)
	if err != nil {
		_ = origin.Close()
		return
	}
	peer := metadata.UDPAddr()
	remote := &sourcePeerPackets{PacketConn: statistic.NewUDPTracker(packet, metadata, statistic.DefaultManager), peer: peer.String()}
	go func() {
		var once sync.Once
		shutdown := func() { once.Do(func() { _ = origin.Close(); _ = remote.Close() }) }
		defer shutdown()
		go func() { defer shutdown(); relayDNSPackets(remote, origin, peer, nil) }()
		relayDNSPackets(origin, remote, nil, nil)
	}()
}

// Keep the existing symmetric-NAT policy in addition to the dialers' peer checks.
type sourcePeerPackets struct {
	net.PacketConn
	peer string
}

func (p *sourcePeerPackets) ReadFrom(b []byte) (int, net.Addr, error) {
	for {
		n, from, err := p.PacketConn.ReadFrom(b)
		if err != nil || from == nil || from.String() == p.peer {
			return n, from, err
		}
	}
}
