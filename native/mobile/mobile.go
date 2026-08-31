// Package mobile exposes the gomobile-compatible Android API.
package mobile

import (
	"errors"
	"strconv"
	"sync"
	"syscall"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

var state struct {
	sync.Mutex
	running bool
	device  device.Device
	stack   *stack.Stack
}

// Start takes ownership of a duplicate of tunFD held by Android's ParcelFileDescriptor.
func Start(tunFD int, rawConfig string, protector Protector, reporter Reporter) error {
	c, err := parseConfig(rawConfig)
	if err != nil {
		if tunFD >= 0 {
			_ = syscall.Close(tunFD)
		}
		return err
	}
	if tunFD < 0 || protector == nil {
		if tunFD >= 0 {
			_ = syscall.Close(tunFD)
		}
		return errors.New("invalid Android VPN bridge")
	}
	state.Lock()
	if state.running {
		state.Unlock()
		_ = syscall.Close(tunFD)
		return errors.New("proxy core is already running")
	}
	defer state.Unlock()
	resetStats()
	dev, err := fdbased.Open(strconv.Itoa(tunFD), 1500, 0)
	if err != nil {
		_ = syscall.Close(tunFD)
		return err
	}
	t := tunnel.T()
	t.SetProxy(&httpsConnectDialer{config: c, protector: protector, reporter: reporter})
	netstack, err := core.CreateStack(&core.Config{LinkEndpoint: dev, TransportHandler: t})
	if err != nil {
		dev.Close()
		return err
	}
	state.device, state.stack, state.running = dev, netstack, true
	report(reporter, "Native TCP/IP stack started; proxy=%s via %s profile=%s DoH=%s", c.displayAddress(), c.address(), c.Profile, c.DoHURL)
	return nil
}

func Stop() {
	state.Lock()
	if !state.running {
		state.Unlock()
		return
	}
	state.running = false
	dev, netstack := state.device, state.stack
	state.device, state.stack = nil, nil
	state.Unlock()
	if dev != nil {
		dev.Close()
	}
	if netstack != nil {
		netstack.Close()
		netstack.Wait()
	}
}
