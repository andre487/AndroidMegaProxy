package mobile

import (
	"context"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestCloseTerminatesLocalBypass(t *testing.T) {
	for _, kind := range []string{"HTTPS", "SSH", "MASQUE"} {
		t.Run(kind, func(t *testing.T) {
			l, err := net.Listen("tcp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer l.Close()
			go func() {
				c, e := l.Accept()
				if e == nil {
					defer c.Close()
					io.Copy(c, c)
				}
			}()
			cfg := config{BypassLocalNetworks: true}
			var dial func(context.Context, string) (net.Conn, error)
			var closeDialer func() error
			switch kind {
			case "HTTPS":
				d := &httpsConnectDialer{config: cfg, protector: &jumpTestProtector{}}
				dial, closeDialer = d.connectTarget, d.Close
			case "SSH":
				d := &sshDialer{config: cfg, protector: &jumpTestProtector{}}
				dial, closeDialer = d.connectTarget, d.Close
			default:
				d := &masqueDialer{config: cfg, protector: &jumpTestProtector{}}
				dial, closeDialer = d.connectTarget, d.Close
			}
			defer closeDialer()
			c, err := dial(context.Background(), l.Addr().String())
			if err != nil {
				t.Fatal(err)
			}
			defer c.Close()
			closeDialer()
			c.SetDeadline(time.Now().Add(time.Second))
			if _, err = c.Write([]byte("ping")); err != nil {
				return
			}
			if _, err = io.ReadFull(c, make([]byte, 4)); err == nil {
				t.Error("LAN socket still transfers bytes after dialer.Close")
			}
		})
	}
}

func TestClosedMasqueRejectsAdmission(t *testing.T) {
	d := &masqueDialer{connections: make(chan struct{}, 1)}
	d.connections <- struct{}{}
	d.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := d.DialContext(ctx, nil); done <- err }()
	select {
	case <-done:
	case <-time.After(200 * time.Millisecond):
		t.Error("already-closed MASQUE dialer blocks on a full admission queue")
	}
	cancel()
	<-ctx.Done()
}

func TestStopUnblocksAdmissionWaiters(t *testing.T) {
	for _, kind := range []string{"SSH", "MASQUE"} {
		t.Run(kind, func(t *testing.T) {
			slots := make(chan struct{}, 1)
			slots <- struct{}{}
			var dial func(context.Context) (net.Conn, error)
			var stop func() error
			if kind == "SSH" {
				d := &sshDialer{channels: slots}
				dial = func(ctx context.Context) (net.Conn, error) { return d.connectTarget(ctx, "example.test:443") }
				stop = d.Close
			} else {
				d := &masqueDialer{connections: slots}
				dial = func(ctx context.Context) (net.Conn, error) { return d.DialContext(ctx, nil) }
				stop = d.Close
			}
			defer stop()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			go func() { _, err := dial(ctx); done <- err }()
			// The occupied slot cannot be released by Close; only lifetime cancellation wakes it.
			stop()
			select {
			case err := <-done:
				if err == nil {
					t.Fatal("closed dial succeeded")
				}
			case <-time.After(time.Second):
				t.Fatal("Stop did not wake admission waiter")
			}
		})
	}
}

func TestMasqueStopClosesBypassedUDP(t *testing.T) {
	server, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	d := &masqueDialer{config: config{BypassLocalNetworks: true}, protector: &jumpTestProtector{}}
	defer d.Close()
	address := server.LocalAddr().(*net.UDPAddr)
	m := &M.Metadata{DstIP: netip.MustParseAddr("127.0.0.1"), DstPort: uint16(address.Port)}
	c, err := d.DialUDP(m)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	d.Close()
	if _, err := c.WriteTo([]byte("after stop"), address); err == nil {
		t.Fatal("bypassed UDP survived Stop")
	}
}
