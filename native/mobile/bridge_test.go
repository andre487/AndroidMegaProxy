package mobile

import (
	"os"
	"strings"
	"testing"
	"time"

	"github.com/xjasonlyu/tun2socks/v2/proxy/reject"
	"github.com/xjasonlyu/tun2socks/v2/tunnel"
	"golang.org/x/sys/unix"
)

func TestBridgeOwnsOnlyDuplicatedDescriptor(t *testing.T) {
	original, err := os.CreateTemp(t.TempDir(), "tun")
	if err != nil {
		t.Fatal(err)
	}
	defer original.Close()
	fd, err := duplicateTunFD(int(original.Fd()))
	if err != nil {
		t.Fatal(err)
	}
	if fd == int(original.Fd()) {
		t.Fatal("descriptor was not duplicated")
	}
	flags, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0)
	if err != nil {
		t.Fatal(err)
	}
	if flags&unix.FD_CLOEXEC == 0 {
		t.Error("duplicate must not survive exec")
	}
	if err := unix.Close(fd); err != nil {
		t.Fatal(err)
	}
	if _, err := original.WriteString("still owned by caller"); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 20; i++ {
		if err := Start(int(original.Fd()), 1400, "invalid json", nil, nil); err == nil {
			t.Fatal("invalid config accepted")
		}
		if _, err := original.Stat(); err != nil {
			t.Fatalf("Start closed borrowed descriptor: %v", err)
		}
	}
	if _, err := duplicateTunFD(-1); err == nil {
		t.Fatal("negative descriptor accepted")
	}
}

type blockingBridgeCloser struct{ entered, release chan struct{} }

func (c *blockingBridgeCloser) Close() error { close(c.entered); <-c.release; return nil }

func TestBridgeStartCannotOverlapStopCleanup(t *testing.T) {
	closer := &blockingBridgeCloser{make(chan struct{}), make(chan struct{})}
	state.Lock()
	state.running = true
	state.proxyCloser = closer
	tunnel.T().SetProxy(&httpsConnectDialer{})
	state.Unlock()
	done := make(chan struct{})
	go func() { Stop(); close(done) }()
	defer func() { close(closer.release); <-done }()
	select {
	case <-closer.entered:
	case <-time.After(time.Second):
		t.Fatal("Stop did not close upstream")
	}
	original, err := os.CreateTemp(t.TempDir(), "tun")
	if err != nil {
		t.Fatal(err)
	}
	defer original.Close()
	raw := `{"host":"proxy.example","dialHost":"192.0.2.1","port":443,"username":"u","password":"p","profile":"CHROME_ANDROID","dohUrl":"https://dns.google/dns-query"}`
	if _, err := parseConfig(raw); err != nil {
		t.Fatal(err)
	}
	err = Start(int(original.Fd()), 1400, raw, &jumpTestProtector{}, nil)
	if err == nil || !strings.Contains(err.Error(), "already running") {
		t.Fatalf("Start during Stop: %v", err)
	}
	if _, ok := tunnel.T().Proxy().(*reject.Reject); !ok {
		t.Fatal("Stop retained the global dialer and its JVM callbacks")
	}
	Stop() // A concurrent second Stop must not clear the first Stop's guard.
	state.Lock()
	stopping := state.stopping
	state.Unlock()
	if !stopping {
		t.Fatal("second Stop cleared cleanup guard")
	}
	if _, err := original.Stat(); err != nil {
		t.Fatalf("rejected Start closed borrowed FD: %v", err)
	}
}
