package mobile

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"io"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

func TestHostKeyTOFU(t *testing.T) {
	public, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	key, err := ssh.NewPublicKey(public)
	if err != nil {
		t.Fatal(err)
	}
	fingerprint := ssh.FingerprintSHA256(key)

	err = hostKeyCallback("", false, "jump")("host", nil, key)
	if err == nil || !strings.Contains(err.Error(), "SSH_HOST_KEY_UNKNOWN|jump|") || !strings.Contains(err.Error(), fingerprint) {
		t.Fatalf("unexpected TOFU error: %v", err)
	}
	if err := hostKeyCallback(fingerprint, false, "jump")("host", nil, key); err != nil {
		t.Fatal(err)
	}
	if err := hostKeyCallback("different", true, "jump")("host", nil, key); err != nil {
		t.Fatal(err)
	}
}

func TestSSHAuthRejectsMalformedPrivateKeyInsteadOfFallingBack(t *testing.T) {
	_, err := sshAuthMethods("not a private key", "password", "AUTO")
	if err == nil || !strings.Contains(err.Error(), "invalid SSH private key") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestSSHPasswordIncludesKeyboardInteractiveFallback(t *testing.T) {
	methods, err := sshAuthMethods("", "password", "AUTO")
	if err != nil {
		t.Fatal(err)
	}
	if len(methods) != 2 {
		t.Fatalf("got %d password auth methods, want password and keyboard-interactive", len(methods))
	}
}

func TestSSHAuthenticationModes(t *testing.T) {
	methods, err := sshAuthMethods("", "password", "KEY_ONLY")
	if err != nil {
		t.Fatal(err)
	}
	if len(methods) != 0 {
		t.Fatalf("key-only unexpectedly used password: %d methods", len(methods))
	}
	methods, err = sshAuthMethods("", "password", "PASSWORD_ONLY")
	if err != nil {
		t.Fatal(err)
	}
	if len(methods) != 2 {
		t.Fatalf("password-only got %d methods", len(methods))
	}
}

type rejectedSSHConn struct {
	ssh.Conn
	closed  bool
	failure error
}

func (c *rejectedSSHConn) OpenChannel(string, []byte) (ssh.Channel, <-chan *ssh.Request, error) {
	return nil, nil, c.failure
}
func (c *rejectedSSHConn) Close() error { c.closed = true; return nil }

func TestSSHChannelFailureIsolation(t *testing.T) {
	for _, failure := range []error{&ssh.OpenChannelError{Reason: ssh.ConnectionFailed}, context.Canceled, context.DeadlineExceeded, io.EOF} {
		t.Run(failure.Error(), func(t *testing.T) {
			conn := &rejectedSSHConn{failure: failure}
			client := &ssh.Client{Conn: conn}
			d := &sshDialer{client: client, config: config{SSHMaxChannels: 2}}
			_, err := d.connectTarget(context.Background(), "example.com:443")
			if err == nil {
				t.Fatal("expected failure")
			}
			wantClosed := failure == io.EOF
			if conn.closed != wantClosed {
				t.Fatalf("session closed=%t want %t", conn.closed, wantClosed)
			}
			if len(d.channels) != 0 {
				t.Fatal("channel slot leaked")
			}
		})
	}
}

func TestSSHOldFailureDoesNotCloseReplacement(t *testing.T) {
	current := &rejectedSSHConn{}
	d := &sshDialer{client: &ssh.Client{Conn: current}}
	d.invalidateClient(&ssh.Client{})
	if current.closed || d.client == nil {
		t.Fatal("old session invalidated its replacement")
	}
}

type blockedSSHConn struct {
	ssh.Conn
	entered chan struct{}
	unblock chan struct{}
	once    sync.Once
}

func (c *blockedSSHConn) OpenChannel(string, []byte) (ssh.Channel, <-chan *ssh.Request, error) {
	close(c.entered)
	<-c.unblock
	return nil, nil, io.EOF
}
func (c *blockedSSHConn) Close() error { c.once.Do(func() { close(c.unblock) }); return nil }

func TestCancelledSSHOpenKeepsAdmissionUntilWorkerEnds(t *testing.T) {
	raw := &blockedSSHConn{entered: make(chan struct{}), unblock: make(chan struct{})}
	defer raw.Close()
	client := &ssh.Client{Conn: raw}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	released := make(chan struct{})
	done := make(chan bool, 1)
	go func() {
		_, _, abandoned := dialSSHChannel(ctx, client, "example.com:443", func() { close(released) }, func() { t.Error("healthy worker aborted") }, time.Hour)
		done <- abandoned
	}()
	<-raw.entered
	cancel()
	select {
	case abandoned := <-done:
		if !abandoned {
			t.Fatal("worker not tracked")
		}
	case <-time.After(time.Second):
		t.Fatal("cancel blocked")
	}
	select {
	case <-released:
		t.Fatal("released slot with worker still blocked")
	default:
	}
	_ = raw.Close()
	select {
	case <-released:
	case <-time.After(time.Second):
		t.Fatal("worker did not release slot after transport closed")
	}
}

func TestClosedSSHDialerCannotReconnect(t *testing.T) {
	d := &sshDialer{config: config{BypassLocalNetworks: true}}
	_ = d.Close()
	if _, err := d.session(context.Background()); err == nil {
		t.Fatal("closed SSH dialer attempted a session")
	}
	if _, err := d.connectTarget(context.Background(), "127.0.0.1:443"); err == nil {
		t.Fatal("closed SSH dialer used direct bypass")
	}
}

func TestAbandonedSSHOpenEventuallyReleasesPool(t *testing.T) {
	raw := &blockedSSHConn{entered: make(chan struct{}), unblock: make(chan struct{})}
	defer raw.Close()
	client := &ssh.Client{Conn: raw}
	d := &sshDialer{client: client}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	released := make(chan struct{})
	done := make(chan bool, 1)
	go func() {
		_, _, abandoned := dialSSHChannel(ctx, client, "example.com:443", func() { close(released) }, func() { d.invalidateClient(client) }, 20*time.Millisecond)
		done <- abandoned
	}()
	<-raw.entered
	cancel()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("caller did not cancel")
	}
	select {
	case <-released:
	case <-time.After(time.Second):
		t.Fatal("unresponsive peer permanently occupied admission")
	}
	d.mu.Lock()
	current := d.client
	d.mu.Unlock()
	if current != nil {
		t.Fatal("stalled session not cleared for next connection")
	}
}
