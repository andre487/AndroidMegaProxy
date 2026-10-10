package mobile

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	quic "github.com/refraction-networking/uquic"
	"github.com/refraction-networking/uquic/http3"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"github.com/xjasonlyu/tun2socks/v2/proxy/reject"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

type masqueLossPacketConn struct {
	net.PacketConn
	drop       atomic.Bool
	packets    atomic.Int32
	dropNumber atomic.Int32
}

type masqueUDPAdapter struct {
	*net.UDPConn
	mu   sync.Mutex
	peer net.Addr
}

func (a *masqueUDPAdapter) ID() stack.TransportEndpointID {
	return stack.TransportEndpointID{LocalAddress: tcpip.AddrFrom4([4]byte{192, 0, 2, 1}), LocalPort: 443, RemoteAddress: tcpip.AddrFrom4([4]byte{10, 77, 0, 1}), RemotePort: 40000}
}
func (a *masqueUDPAdapter) ReadFrom(b []byte) (int, net.Addr, error) {
	n, peer, e := a.UDPConn.ReadFrom(b)
	a.mu.Lock()
	if a.peer == nil {
		a.peer = peer
	}
	a.mu.Unlock()
	return n, peer, e
}
func (a *masqueUDPAdapter) WriteTo(b []byte, _ net.Addr) (int, error) {
	a.mu.Lock()
	peer := a.peer
	a.mu.Unlock()
	return a.UDPConn.WriteTo(b, peer)
}
func TestMASQUEOversizedPacketPreservesUDPFlow(t *testing.T) {
	cfg := masqueTestServer(t)
	cfg.Profile = "FIREFOX_ANDROID"
	d := &masqueDialer{config: cfg, protector: &jumpTestProtector{}}
	defer d.Close()
	listener, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if e != nil {
		t.Fatal(e)
	}
	a := &masqueUDPAdapter{UDPConn: listener}
	defer a.Close()
	client, e := net.DialUDP("udp4", nil, listener.LocalAddr().(*net.UDPAddr))
	if e != nil {
		t.Fatal(e)
	}
	defer client.Close()
	tun := tunnel.T()
	tun.SetProxy(d)
	t.Cleanup(func() { tun.SetProxy(&reject.Reject{}); tun.SetUDPTimeout(60 * time.Second) })
	tun.SetUDPTimeout(2 * time.Second)
	tun.ProcessAsync()
	tun.HandleUDP(a)
	_ = client.SetDeadline(time.Now().Add(time.Second))
	_, _ = client.Write([]byte("small control"))
	b := make([]byte, 2000)
	if _, e := client.Read(b); e != nil {
		t.Fatal("positive UDP control:", e)
	}
	_, _ = client.Write(make([]byte, 1200))
	time.Sleep(20 * time.Millisecond)
	_, _ = client.Write([]byte("small after oversized"))
	_ = client.SetReadDeadline(time.Now().Add(time.Second))
	if _, e := client.Read(b); e != nil {
		t.Fatalf("one oversized packet killed the existing UDP flow; subsequent small packet was not forwarded: %v", e)
	}
}

func (p *masqueLossPacketConn) ReadFrom(b []byte) (int, net.Addr, error) {
	for {
		n, a, e := p.PacketConn.ReadFrom(b)
		count := p.packets.Add(1)
		if e != nil || (!p.drop.Load() && count != p.dropNumber.Load()) {
			return n, a, e
		}
	}
}
func TestMASQUEGoAwayReconnectsWithoutInterruptingActiveStream(t *testing.T) {
	fixture := newMasqueFixture(t, nil)
	d := &masqueDialer{config: fixture.config, protector: &jumpTestProtector{}}
	defer d.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	first, err := d.connectTarget(ctx, "first.example:443")
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	old := d.session
	shutdownDone := make(chan error, 1)
	go func() { shutdownDone <- fixture.server.Shutdown(ctx) }()
	select {
	case <-fixture.serving:
	case <-ctx.Done():
		t.Fatal("server did not stop accepting")
	}
	// Keep the same QUIC listener/socket; the replacement server accepts new
	// connections while the old server drains its existing CONNECT stream.
	replacement := &http3.Server{EnableDatagrams: true, Handler: fixture.server.Handler}
	go func() { _ = replacement.ServeListener(fixture.listener) }()
	t.Cleanup(func() { _ = replacement.Close() })
	for old.client.CanTakeNewRequest() {
		select {
		case <-ctx.Done():
			t.Fatal("GOAWAY was not received")
		case <-time.After(time.Millisecond):
		}
	}
	second, err := d.connectTarget(ctx, "next.example:443")
	if err != nil {
		t.Fatal("new CONNECT after GOAWAY:", err)
	}
	defer second.Close()
	if d.session == old {
		t.Fatal("GOAWAY session was reused")
	}
	for _, conn := range []net.Conn{first, second} {
		_ = conn.SetDeadline(time.Now().Add(time.Second))
		if _, err := conn.Write([]byte("echo")); err != nil {
			t.Fatal(err)
		}
		b := make([]byte, 4)
		if _, err := io.ReadFull(conn, b); err != nil || string(b) != "echo" {
			t.Fatalf("active stream lost: %q %v", b, err)
		}
	}
	_ = d.Close()
	select {
	case <-old.conn.Context().Done():
	case <-time.After(time.Second):
		t.Fatal("Stop leaked draining session")
	}
	select {
	case <-shutdownDone:
	case <-time.After(time.Second):
		t.Fatal("draining server did not finish")
	}
}

func TestMASQUEBlockedUDPWriteDeadlineAndClose(t *testing.T) {
	var packet *masqueLossPacketConn
	fixture := newMasqueFixture(t, func(conn net.PacketConn) net.PacketConn {
		packet = &masqueLossPacketConn{PacketConn: conn}
		return packet
	})
	cfg := fixture.config
	d := &masqueDialer{config: cfg, protector: &jumpTestProtector{}}
	defer d.Close()
	pc, e := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr("192.0.2.1"), DstPort: 443})
	if e != nil {
		t.Fatal(e)
	}
	defer pc.Close()
	target := &net.UDPAddr{IP: net.ParseIP("192.0.2.1"), Port: 443}
	packet.drop.Store(true)
	// First fill the shared send queue with no deadline. Then update the
	// deadline of the already-blocked WriteTo, as net.PacketConn requires.
	_ = pc.SetWriteDeadline(time.Time{})
	done := make(chan error, 1)
	var writes atomic.Int64
	go func() {
		for i := 0; i < 100000; i++ {
			if _, e := pc.WriteTo(make([]byte, 1100), target); e != nil {
				done <- e
				return
			}
			writes.Add(1)
		}
		done <- nil
	}()
	for end := time.Now().Add(time.Second); time.Now().Before(end); {
		previous := writes.Load()
		time.Sleep(15 * time.Millisecond)
		if previous > 32 && writes.Load() == previous {
			break
		}
	}
	_ = pc.SetWriteDeadline(time.Now().Add(20 * time.Millisecond))
	select {
	case e := <-done:
		if !errors.Is(e, os.ErrDeadlineExceeded) {
			t.Fatalf("blocked write deadline: %v", e)
		}
		t.Logf("write returned: %v", e)
	case <-time.After(time.Second):
		_ = pc.Close()
		select {
		case <-done:
			t.Fatal("blocked write ignored the updated deadline but unblocked after stream Close")
		case <-time.After(time.Second):
			_ = d.Close()
			<-done
			t.Fatal("blocked write ignored the updated deadline and stream Close; only whole QUIC connection Close unblocked it")
		}
	}
	// A second blocked write must also wake on stream Close, without closing
	// the shared QUIC connection (other tunnels may still use it).
	_ = pc.SetWriteDeadline(time.Time{})
	closed := make(chan error, 1)
	go func() { _, err := pc.WriteTo(make([]byte, 1100), target); closed <- err }()
	select {
	case err := <-closed:
		t.Fatalf("queue was not full: %v", err)
	case <-time.After(20 * time.Millisecond):
	}
	_ = pc.Close()
	select {
	case err := <-closed:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatalf("closed write: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("stream Close did not cancel UDP write")
	}
	if d.session.conn.Context().Err() != nil {
		t.Fatal("closing UDP flow closed shared QUIC session")
	}

}

func TestMASQUEInitialPacketLoss(t *testing.T) {
	for _, profile := range []string{"CHROME_ANDROID", "FIREFOX_ANDROID", "RANDOMIZED", "CUSTOM"} {
		for _, number := range []int32{1, 2} {
			t.Run(profile+"_drop_"+string(rune('0'+number)), func(t *testing.T) {
				var packet *masqueLossPacketConn
				fixture := newMasqueFixture(t, func(conn net.PacketConn) net.PacketConn {
					packet = &masqueLossPacketConn{PacketConn: conn}
					return packet
				})
				cfg := fixture.config
				cfg.Profile = profile
				cfg.CustomJA3 = "771,4865-4866-4867,0-10-13-16-43-51-57,29-23,0"
				packet.dropNumber.Store(number)
				d := &masqueDialer{config: cfg, protector: &jumpTestProtector{}}
				defer d.Close()
				ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
				defer cancel()
				c, e := d.connectTarget(ctx, "target.example:443")
				if e != nil {
					t.Fatalf("lost Initial packet prevents connection: %v", e)
				}
				defer c.Close()
				_ = c.SetDeadline(time.Now().Add(time.Second))
				if _, e = c.Write([]byte("echo")); e != nil {
					t.Fatal(e)
				}
				b := make([]byte, 4)
				if _, e = io.ReadFull(c, b); e != nil {
					t.Fatal(e)
				}
			})
		}
	}
}

func TestMASQUEAuthAndQUICDiagnosticsOmitPrivateValues(t *testing.T) {
	cfg := masqueTestServer(t)
	cfg.Username, cfg.Password = "private-user-marker", "private-password-marker"
	recorder := &diagnosticRecorder{}
	d := &masqueDialer{config: cfg, protector: &jumpTestProtector{}, reporter: recorder}
	defer d.Close()
	_, err := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr("192.0.2.1"), DstPort: 443})
	if err == nil || errorClass(err) != "proxy_authentication" {
		t.Fatalf("UDP auth failure: %v", err)
	}
	logs := recorder.text()
	for _, required := range []string{"tls_version=TLS1.3", "alpn=h3", "cipher=", "quic_version=", "datagrams=true", "extended_connect=true", "session=", "conn=", "status=407", "reason=proxy_authentication", "udp=true"} {
		if !strings.Contains(logs, required) {
			t.Fatalf("missing %q in logs: %s", required, logs)
		}
	}
	for _, secret := range []string{cfg.Username, cfg.Password, cfg.Host, cfg.DialHost, "192.0.2.1", "Certificate"} {
		if strings.Contains(logs, secret) {
			t.Fatalf("private value %q leaked: %s", secret, logs)
		}
	}
	for _, peerError := range []error{
		&quic.ApplicationError{Remote: true, ErrorCode: 0x100, ErrorMessage: "private-peer-message"},
		&quic.TransportError{Remote: true, ErrorCode: 1, ErrorMessage: "private-peer-message"},
	} {
		got := quicErrorDetails(peerError)
		if strings.Contains(got, "private-peer-message") || !strings.Contains(got, "remote=true") || !strings.Contains(got, "quic_error_code=") {
			t.Fatal(got)
		}
	}
	if errorClass(errMasqueSettings) != "unsupported_server_settings" || errorClass(errMasqueCapsuleProtocol) != "missing_capsule_protocol" {
		t.Fatal("missing server capability failure classification")
	}
	if got := sshFailureDetails(errors.New("private-peer-message")); got != "reason=other" {
		t.Fatalf("raw SSH error logged: %s", got)
	}
}

func TestMASQUELocalCloseDoesNotReportBlocking(t *testing.T) {
	cfg := masqueTestServer(t)
	recorder := &diagnosticRecorder{}
	d := &masqueDialer{config: cfg, protector: &jumpTestProtector{}, reporter: recorder}
	defer d.Close()
	conn, err := d.connectTarget(context.Background(), "target.example:443")
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { _, err := conn.Read(make([]byte, 1)); done <- err }()
	_ = conn.Close()
	select {
	case err := <-done:
		if !errors.Is(err, net.ErrClosed) {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("closed TCP read blocked")
	}
	packet, err := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr("192.0.2.1"), DstPort: 443})
	if err != nil {
		t.Fatal(err)
	}
	_ = packet.Close()
	// Wait for the datagram receiver to finish so late close diagnostics count.
	p := packet.(*masquePacketConn)
	select {
	case <-p.packets:
	case <-time.After(time.Second):
		t.Fatal("UDP receiver did not stop")
	}
	if logs := recorder.text(); strings.Contains(logs, "result=failed") || strings.Contains(logs, "dpi_hint=possible") {
		t.Fatalf("local Close looked like network blocking: %s", logs)
	}
}
