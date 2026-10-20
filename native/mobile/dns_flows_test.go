package mobile

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"github.com/xjasonlyu/tun2socks/v2/tunnel/statistic"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

func TestDNSFlowsBoundAdmissionAndCloseWithOwner(t *testing.T) {
	metadata := &M.Metadata{DstIP: netip.MustParseAddr("10.77.0.2"), DstPort: 53}
	for _, kind := range []string{"HTTPS", "SSH", "MASQUE", "SOCKS5"} {
		t.Run(kind, func(t *testing.T) {
			cfg := config{DoHURL: "https://dns.example/dns-query"}
			var dial func(*M.Metadata) (net.PacketConn, error)
			var owner io.Closer
			switch kind {
			case "HTTPS":
				d := &httpsConnectDialer{config: cfg}
				dial, owner = d.DialUDP, d
			case "SSH":
				d := &sshDialer{config: cfg}
				dial, owner = d.DialUDP, d
			case "MASQUE":
				d := &masqueDialer{config: cfg}
				dial, owner = d.DialUDP, d
			default:
				d := &socks5Dialer{config: cfg}
				dial, owner = d.DialUDP, d
			}
			defer owner.Close()
			var flows []net.PacketConn
			for i := 0; i < maxDNSFlows; i++ {
				pc, err := dial(metadata)
				if err != nil {
					t.Fatal(err)
				}
				flows = append(flows, pc)
			}
			if pc, err := dial(metadata); err == nil {
				pc.Close()
				t.Fatal("flow limit missing")
			}
			flows[0].Close()
			replacement, err := dial(metadata)
			if err != nil {
				t.Fatal("slot not released", err)
			}
			flows = append(flows, replacement)
			owner.Close()
			for _, flow := range flows {
				if _, _, err := flow.ReadFrom(make([]byte, 12)); !errors.Is(err, net.ErrClosed) {
					t.Fatal("flow survived owner", err)
				}
			}
			if pc, err := dial(metadata); err == nil {
				pc.Close()
				t.Fatal("admission after close")
			}
		})
	}
}

func TestSSHDNSStreamsShareUDPAdmissionBudget(t *testing.T) {
	d := &sshDialer{config: config{SSHMaxChannels: 1, DoHURL: "https://dns.example"}}
	defer d.Close()
	metadata := &M.Metadata{DstIP: netip.MustParseAddr("10.77.0.2"), DstPort: 53}
	var conns []net.Conn
	for i := 0; i < maxDNSFlows; i++ {
		conn, err := d.DialContext(context.Background(), metadata)
		if err != nil {
			t.Fatal(err)
		}
		conns = append(conns, conn)
	}
	if pc, err := d.DialUDP(metadata); err == nil {
		pc.Close()
		t.Fatal("TCP and UDP budgets differ")
	}
	if conn, err := d.DialContext(context.Background(), metadata); err == nil {
		conn.Close()
		t.Fatal("TCP admission unbounded")
	}
	d.Close()
	for _, conn := range conns {
		if _, err := conn.Read(make([]byte, 1)); err == nil {
			t.Fatal("stream survived close")
		}
	}
}

type dnsLifecycleProxy struct {
	*httpsConnectDialer
	packets chan net.PacketConn
}

func (p *dnsLifecycleProxy) DialUDP(m *M.Metadata) (net.PacketConn, error) {
	pc, err := p.httpsConnectDialer.DialUDP(m)
	if err == nil {
		p.packets <- pc
	}
	return pc, err
}

type dnsLifecycleOrigin struct {
	net.Conn
	id    stack.TransportEndpointID
	first atomic.Bool
	query []byte
	ready chan struct{}
}

func (p *dnsLifecycleOrigin) ID() stack.TransportEndpointID { return p.id }
func (p *dnsLifecycleOrigin) ReadFrom(b []byte) (int, net.Addr, error) {
	if p.first.CompareAndSwap(false, true) {
		return copy(b, p.query), nil, nil
	}
	n, err := p.Conn.Read(b)
	return n, nil, err
}
func (p *dnsLifecycleOrigin) WriteTo(b []byte, _ net.Addr) (int, error) {
	p.ready <- struct{}{}
	return len(b), nil
}

func TestRealTunnelReleasesIdleDNSFlowsOnProxyClose(t *testing.T) {
	const count = 64
	proxy := &dnsLifecycleProxy{httpsConnectDialer: &httpsConnectDialer{config: config{DoHURL: "https://dns.example"}}, packets: make(chan net.PacketConn, count)}
	transport := tunnel.New(proxy, statistic.DefaultManager)
	transport.ProcessAsync()
	defer transport.Close()
	ready := make(chan struct{}, count)
	query, _, _ := buildAQuery("example.test")
	binary.BigEndian.PutUint16(query[len(query)-4:], 28)
	baseline := len(statistic.DefaultManager.Snapshot().Connections)
	var origins []net.Conn
	for i := 0; i < count; i++ {
		a, b := net.Pipe()
		defer b.Close()
		origins = append(origins, a)
		origin := &dnsLifecycleOrigin{Conn: a, query: query, ready: ready, id: stack.TransportEndpointID{LocalAddress: tcpip.AddrFrom4([4]byte{10, 77, 0, 2}), LocalPort: 53, RemoteAddress: tcpip.AddrFrom4([4]byte{10, 77, 0, 1}), RemotePort: uint16(10000 + i)}}
		transport.HandleUDP(origin)
	}
	var packets []net.PacketConn
	defer func() {
		for _, pc := range packets {
			pc.Close()
		}
	}()
	for i := 0; i < count; i++ {
		select {
		case <-ready:
		case <-time.After(time.Second):
			t.Fatal("DNS fixture did not answer")
		}
		packets = append(packets, <-proxy.packets)
	}
	proxy.Close()
	for _, origin := range origins {
		origin.Close()
	}
	deadline := time.Now().Add(time.Second)
	for len(statistic.DefaultManager.Snapshot().Connections) > baseline && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if retained := len(statistic.DefaultManager.Snapshot().Connections) - baseline; retained != 0 {
		t.Fatal("DNS flows retained after close", retained)
	}
}

func TestDNSAdmissionRacesWithClose(t *testing.T) {
	var flows dnsFlows
	var workers sync.WaitGroup
	start := make(chan struct{})
	for i := 0; i < 8; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			<-start
			for j := 0; j < 100; j++ {
				packet, err := flows.open(func() *dohPacketConn {
					return newDoHPacketConnWithClient(config{}, nil, nil, "https://dns.example", nil, nil)
				})
				if err == nil {
					_ = packet.Close()
				}
			}
		}()
	}
	close(start)
	flows.close()
	workers.Wait()
	if len(flows.packets) != 0 {
		t.Fatal("DNS admission leaked after close")
	}
}
