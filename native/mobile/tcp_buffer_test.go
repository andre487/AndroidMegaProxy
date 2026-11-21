package mobile

import (
	"testing"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
)

func TestTCPStackSendBufferPolicy(t *testing.T) {
	for _, test := range []struct {
		name      string
		validator SourceValidator
	}{
		{name: "normal"},
		{name: "strict", validator: &sourceTestValidator{}},
	} {
		t.Run(test.name, func(t *testing.T) {
			endpoint := channel.New(8, 1400, "")
			defer endpoint.Close()
			s, err := core.CreateStack(sourceStackConfig(endpoint, dnsTransport{}, test.validator, 0))
			if err != nil {
				t.Fatal(err)
			}
			defer func() {
				s.Close()
				s.Wait()
			}()

			var send tcpip.TCPSendBufferSizeRangeOption
			if err := s.TransportProtocolOption(tcp.ProtocolNumber, &send); err != nil {
				t.Fatal(err)
			}
			if send.Min != tcp.MinBufferSize || send.Default != 32<<10 || send.Max != 128<<10 {
				t.Fatalf("unexpected TCP send buffer limits: %+v", send)
			}
			var recovery tcpip.TCPRecovery
			if err := s.TransportProtocolOption(tcp.ProtocolNumber, &recovery); err != nil {
				t.Fatal(err)
			}
			if recovery&tcpip.TCPRACKLossDetection == 0 {
				t.Fatal("RACK loss detection is disabled")
			}
			var receive tcpip.TCPReceiveBufferSizeRangeOption
			if err := s.TransportProtocolOption(tcp.ProtocolNumber, &receive); err != nil {
				t.Fatal(err)
			}
			if receive.Default != tcp.DefaultReceiveBufferSize || receive.Max != tcp.MaxBufferSize {
				t.Fatalf("receive buffer policy changed: %+v", receive)
			}
		})
	}
}
