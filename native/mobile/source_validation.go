package mobile

import (
	"errors"
	"net"
	"sync"
	"time"

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
func StartStrict(tunFD int, mtu int, rawConfig string, protector Protector, reporter Reporter, validator SourceValidator, udpRecheckMillis int) error {
	if validator == nil {
		return errors.New("strict source validator is required")
	}
	if udpRecheckMillis != 0 && udpRecheckMillis != 1000 {
		return errors.New("unsupported UDP source validation interval")
	}
	return start(tunFD, mtu, rawConfig, protector, reporter, validator, time.Duration(udpRecheckMillis)*time.Millisecond)
}

type sourceTransport struct {
	adapter.TransportHandler
	validator  SourceValidator
	slots      chan struct{}
	udpRecheck time.Duration
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
		guarded := &sourceUDP{UDPConn: conn, validator: t.validator, recheckInterval: t.udpRecheck}
		if t.udpRecheck > 0 {
			guarded.recheckAt = time.Now().Add(t.udpRecheck)
		}
		t.TransportHandler.HandleUDP(guarded)
	})
}

type sourceUDP struct {
	adapter.UDPConn
	validator       SourceValidator
	recheckInterval time.Duration
	mu              sync.Mutex
	recheckAt       time.Time
	denied          bool
}

func (c *sourceUDP) allowed() bool {
	if c.recheckInterval == 0 {
		return sourceAllowed(c.validator, 17, c.ID())
	}
	return c.allowedAt(time.Now())
}

func (c *sourceUDP) allowedAt(now time.Time) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.denied {
		return false
	}
	if now.Before(c.recheckAt) {
		return true
	}
	if !sourceAllowed(c.validator, 17, c.ID()) {
		c.denied = true
		return false
	}
	// ponytail: one-second permission window per UDP flow; use per-datagram mode for stricter checks.
	c.recheckAt = now.Add(c.recheckInterval)
	return true
}

func (c *sourceUDP) Read(p []byte) (int, error) {
	n, err := c.UDPConn.Read(p)
	if (err == nil || n > 0) && !c.allowed() {
		_ = c.Close()
		return 0, errors.New("UDP source not authorized")
	}
	return n, err
}

func (c *sourceUDP) ReadFrom(p []byte) (int, net.Addr, error) {
	n, address, err := c.UDPConn.ReadFrom(p)
	if (err == nil || n > 0) && !c.allowed() {
		_ = c.Close()
		return 0, nil, errors.New("UDP source not authorized")
	}
	return n, address, err
}

// Selection happens only at startup. Disabled sessions retain both original objects.
func sourceStackConfig(endpoint stack.LinkEndpoint, handler adapter.TransportHandler, validator SourceValidator, udpRecheck time.Duration) *core.Config {
	cfg := &core.Config{LinkEndpoint: endpoint, TransportHandler: handler}
	if validator != nil {
		cfg.TransportHandler = sourceTransport{TransportHandler: handler, validator: validator, slots: make(chan struct{}, 64), udpRecheck: udpRecheck}
		cfg.LinkEndpoint = &sourceLinkEndpoint{LinkEndpoint: endpoint}
	}
	return cfg
}
