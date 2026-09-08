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

	"golang.org/x/sys/unix"

	"github.com/xjasonlyu/tun2socks/v2/core"
	"github.com/xjasonlyu/tun2socks/v2/core/device"
	"github.com/xjasonlyu/tun2socks/v2/core/device/fdbased"
	"github.com/xjasonlyu/tun2socks/v2/proxy/reject"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

var state struct {
	sync.Mutex
	generation  uint64
	starting    bool
	running     bool
	stopping    bool
	device      device.Device
	stack       *stack.Stack
	proxyCloser io.Closer
}

// Start borrows tunFD for this call. Android keeps its ParcelFileDescriptor open;
// Go owns only the duplicate made here, including every failure path.
func Start(tunFD int, mtu int, rawConfig string, protector Protector, reporter Reporter) error {
	tunFD, err := duplicateTunFD(tunFD)
	if err != nil {
		return err
	}
	c, err := parseConfig(rawConfig)
	if err != nil {
		if tunFD >= 0 {
			_ = syscall.Close(tunFD)
		}
		return err
	}
	if protector == nil || mtu < 1280 || mtu > 65535 {
		if tunFD >= 0 {
			_ = syscall.Close(tunFD)
		}
		return errors.New("invalid Android VPN bridge")
	}
	state.Lock()
	if state.running || state.starting || state.stopping {
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
		// The global tunnel must not retain a failed dialer and its Java callbacks.
		tunnel.T().SetProxy(&reject.Reject{})
		state.starting = false
		state.Unlock()
	}()
	resetStats()
	dev, err := fdbased.Open(strconv.Itoa(tunFD), uint32(mtu), 0)
	if err != nil {
		_ = syscall.Close(tunFD)
		return err
	}
	t := tunnel.T()
	var proxyCloser io.Closer
	if c.isHTTPS() {
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
		if proxyCloser != nil {
			_ = proxyCloser.Close()
		}
		return err
	}
	state.Lock()
	if state.generation != generation || !state.starting {
		state.Unlock()
		dev.Close()
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

func duplicateTunFD(fd int) (int, error) {
	if fd < 0 {
		return -1, errors.New("invalid Android TUN descriptor")
	}
	return unix.FcntlInt(uintptr(fd), unix.F_DUPFD_CLOEXEC, 0)
}

func Stop() {
	state.Lock()
	state.generation++
	if state.stopping {
		state.Unlock()
		return
	}
	state.stopping = true
	// Drop the global strong reference to the dialer, config and JVM service callbacks.
	// Late queued packets must fail closed rather than use a replacement connection.
	tunnel.T().SetProxy(&reject.Reject{})
	state.running = false
	dev, netstack, proxyCloser := state.device, state.stack, state.proxyCloser
	state.device, state.stack, state.proxyCloser = nil, nil, nil
	state.Unlock()
	defer func() {
		state.Lock()
		state.stopping = false
		state.Unlock()
	}()
	// Wake blocked upstream operations before waiting for the stack to finish.
	if proxyCloser != nil {
		_ = proxyCloser.Close()
	}
	if dev != nil {
		dev.Close()
	}
	if netstack != nil {
		netstack.Close()
		netstack.Wait()
	}
}
