// Package mobile exposes the gomobile-compatible Android API.
package mobile

import (
	"context"
	"errors"
	"io"
	"strconv"
	"sync"
	"syscall"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

var state struct {
	sync.Mutex
	generation  uint64
	starting    bool
	running     bool
	device      device.Device
	stack       *stack.Stack
	proxyCloser io.Closer
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
	if state.running || state.starting {
		state.Unlock()
		_ = syscall.Close(tunFD)
		return errors.New("proxy core is already running")
	}
	state.generation++
	generation := state.generation
	state.starting = true
	state.Unlock()
	committed := false
	defer func() {
		if committed {
			return
		}
		state.Lock()
		state.starting = false
		state.Unlock()
	}()
	resetStats()
	dev, err := fdbased.Open(strconv.Itoa(tunFD), 1500, 0)
	if err != nil {
		_ = syscall.Close(tunFD)
		return err
	}
	t := tunnel.T()
	var proxyCloser io.Closer
	if c.Type == "HTTPS" {
		httpsProxy := &httpsConnectDialer{config: c, protector: protector, reporter: reporter}
		t.SetProxy(httpsProxy)
		proxyCloser = httpsProxy
	} else {
		sshProxy := &sshDialer{config: c, protector: protector, reporter: reporter}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		_, sessionErr := sshProxy.session(ctx)
		cancel()
		if sessionErr != nil {
			dev.Close()
			report(reporter, "event=ssh_session result=failed detail=%s", sessionErr)
			return sessionErr
		}
		t.SetProxy(sshProxy)
		proxyCloser = sshProxy
	}
	netstack, err := core.CreateStack(&core.Config{LinkEndpoint: dev, TransportHandler: t})
	if err != nil {
		dev.Close()
		return err
	}
	state.Lock()
	if state.generation != generation || !state.starting {
		state.Unlock()
		netstack.Close()
		netstack.Wait()
		if proxyCloser != nil {
			_ = proxyCloser.Close()
		}
		return errors.New("proxy core start was superseded")
	}
	state.device, state.stack, state.proxyCloser = dev, netstack, proxyCloser
	state.starting, state.running = false, true
	committed = true
	state.Unlock()
	report(reporter, "event=native_stack result=started type=%s fingerprint=%s ssh_profile=%s ipv6=%t bypass_local=%t", c.Type, c.Profile, c.SSHProfile, c.AllowIPv6, c.BypassLocalNetworks)
	return nil
}

func Stop() {
	state.Lock()
	state.generation++
	state.running = false
	dev, netstack, proxyCloser := state.device, state.stack, state.proxyCloser
	state.device, state.stack, state.proxyCloser = nil, nil, nil
	state.Unlock()
	if dev != nil {
		dev.Close()
	}
	if netstack != nil {
		netstack.Close()
		netstack.Wait()
	}
	if proxyCloser != nil {
		_ = proxyCloser.Close()
	}
}
