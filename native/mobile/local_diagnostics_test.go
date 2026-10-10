package mobile

import (
	"context"
	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"net"
	"net/netip"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestLocalResetNeverMarksProxyFailure(t *testing.T) {
	for _, kind := range []string{"HTTPS", "MASQUE"} {
		t.Run(kind, func(t *testing.T) {
			listener, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			go func() {
				c, err := listener.Accept()
				if err != nil {
					return
				}
				_ = c.(*net.TCPConn).SetLinger(0)
				_ = c.Close()
			}()
			var logs []string
			reporter := diagnosticFunc(func(message string) { logs = append(logs, message) })
			c := config{BypassLocalNetworks: true}
			var connection net.Conn
			if kind == "HTTPS" {
				d := &httpsConnectDialer{config: c, protector: &jumpTestProtector{}, reporter: reporter}
				defer d.Close()
				connection, err = d.connectTarget(context.Background(), listener.Addr().String())
			} else {
				d := &masqueDialer{config: c, protector: &jumpTestProtector{}, reporter: reporter}
				defer d.Close()
				connection, err = d.connectTarget(context.Background(), listener.Addr().String())
			}
			if err != nil {
				t.Fatal(err)
			}
			defer connection.Close()
			_ = connection.SetReadDeadline(time.Now().Add(time.Second))
			_, err = connection.Read(make([]byte, 1))
			if err == nil {
				t.Fatal("expected real TCP RST")
			}
			targetReset := false
			for _, message := range logs {
				if strings.Contains(message, "scope=target") && strings.Contains(message, "reason=reset") {
					targetReset = true
				}
				if strings.Contains(message, "scope=proxy") && strings.Contains(message, "reason=reset") {
					t.Fatalf("LAN reset incorrectly triggers proxy recovery: %s", message)
				}
			}
			if !targetReset {
				t.Fatalf("reset diagnostic missing: %v", logs)
			}
		})
	}
}

func TestLocalUDPResetNeverMarksProxyFailure(t *testing.T) {
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	var logs []string
	d := &masqueDialer{config: config{BypassLocalNetworks: true}, protector: &jumpTestProtector{},
		reporter: diagnosticFunc(func(message string) { logs = append(logs, message) })}
	defer d.Close()
	packet, err := d.DialUDP(&M.Metadata{DstIP: netip.MustParseAddr("127.0.0.1"), DstPort: uint16(server.LocalAddr().(*net.UDPAddr).Port)})
	if err != nil {
		t.Fatal(err)
	}
	defer packet.Close()
	direct := packet.(*masqueDirectPacketConn)
	// Inject a platform socket error after the production UDP dial, without relying on ICMP delivery.
	direct.Conn.(*diagnosticConn).reportError("read", &net.OpError{Op: "read", Net: "udp", Err: syscall.ECONNRESET})
	if len(logs) != 1 || !strings.Contains(logs[0], "scope=target") || strings.Contains(logs[0], "dpi_hint=possible") {
		t.Fatalf("local UDP error incorrectly classified: %v", logs)
	}
}
