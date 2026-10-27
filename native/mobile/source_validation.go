package mobile

import (
	"errors"
	"net"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/adapter"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// SourceValidator is implemented by the active Android VPN. Unknown owners fail closed.
// Addresses are numeric, and the source is always the Android application socket.
type SourceValidator interface {
	Allow(protocol int, source string, sourcePort int, destination string, destinationPort int) bool
}

// StartStrict uses a separate entry point so normal Start keeps its existing data path.
func StartStrict(tunFD int, mtu int, rawConfig string, protector Protector, reporter Reporter, validator SourceValidator) error {
	if validator == nil {
		return errors.New("strict source validator is required")
	}
	return start(tunFD, mtu, rawConfig, protector, reporter, validator)
}

type sourceTransport struct {
	adapter.TransportHandler
	validator SourceValidator
	slots     chan struct{}
}

func sourceAllowed(validator SourceValidator, protocol int, id stack.TransportEndpointID) bool {
	return validator.Allow(protocol, net.IP(id.RemoteAddress.AsSlice()).String(), int(id.RemotePort), net.IP(id.LocalAddress.AsSlice()).String(), int(id.LocalPort))
}

// Bound slow Android lookups without stalling the shared TUN receive loop.
func (t sourceTransport) dispatch(conn interface{ Close() error }, run func()) {
	select {
	case t.slots <- struct{}{}:
		go func() { defer func() { <-t.slots }(); run() }()
	default:
		_ = conn.Close()
	}
}
func (t sourceTransport) HandleTCP(conn adapter.TCPConn) {
	t.dispatch(conn, func() {
		if !sourceAllowed(t.validator, 6, conn.ID()) {
			_ = conn.Close()
			return
		}
		t.TransportHandler.HandleTCP(conn)
	})
}

func (t sourceTransport) HandleUDP(conn adapter.UDPConn) {
	t.dispatch(conn, func() {
		if !sourceAllowed(t.validator, 17, conn.ID()) {
			_ = conn.Close()
			return
		}
		// No positive cache: a closed Android UDP socket's port can belong to a different UID.
		t.TransportHandler.HandleUDP(&sourceUDP{UDPConn: conn, validator: t.validator})
	})
}

type sourceUDP struct {
	adapter.UDPConn
	validator SourceValidator
}

func (c *sourceUDP) Read(p []byte) (int, error) {
	n, err := c.UDPConn.Read(p)
	if (err == nil || n > 0) && !sourceAllowed(c.validator, 17, c.ID()) {
		_ = c.Close()
		return 0, errors.New("UDP source not authorized")
	}
	return n, err
}

func (c *sourceUDP) ReadFrom(p []byte) (int, net.Addr, error) {
	n, address, err := c.UDPConn.ReadFrom(p)
	if (err == nil || n > 0) && !sourceAllowed(c.validator, 17, c.ID()) {
		_ = c.Close()
		return 0, nil, errors.New("UDP source not authorized")
	}
	return n, address, err
}

// Selection happens only at startup. Disabled sessions retain both original objects.
func sourceStackConfig(endpoint stack.LinkEndpoint, handler adapter.TransportHandler, validator SourceValidator) *core.Config {
	cfg := &core.Config{LinkEndpoint: endpoint, TransportHandler: handler}
	if validator != nil {
		cfg.TransportHandler = sourceTransport{TransportHandler: handler, validator: validator, slots: make(chan struct{}, 64)}
		cfg.LinkEndpoint = &sourceLinkEndpoint{LinkEndpoint: endpoint}
	}
	return cfg
}
