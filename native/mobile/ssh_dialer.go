package mobile

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"golang.org/x/crypto/ssh"
)

// sshDialer implements OpenSSH-style dynamic forwarding. SOCKS CONNECT semantics
// are mapped directly to SSH direct-tcpip channels; SSH has no general UDP relay.
type sshDialer struct {
	config         config
	protector      Protector
	reporter       Reporter
	mu             sync.Mutex
	client         *ssh.Client
	jumpClient     *ssh.Client
	channels       chan struct{}
	sessionCreated time.Time
	sessionBytes   atomic.Uint64
	keepaliveStop  chan struct{}
	dohClient      *http.Client
	dohInFlight    chan struct{}
}

func (d *sshDialer) DialContext(ctx context.Context, metadata *M.Metadata) (net.Conn, error) {
	return d.connectTarget(ctx, metadata.DestinationAddress())
}

func (d *sshDialer) connectTarget(ctx context.Context, target string) (net.Conn, error) {
	if !d.config.AllowIPv6 {
		host, _, _ := net.SplitHostPort(target)
		if ip := net.ParseIP(host); ip != nil && ip.To4() == nil {
			return nil, errors.New("IPv6 destination blocked by IPv4-only mode")
		}
	}
	if d.config.BypassLocalNetworks && isLocalNetworkTarget(target) {
		return d.protectedDialer().DialContext(ctx, "tcp", target)
	}
	if d.rotationDue() {
		d.invalidate()
	}
	d.mu.Lock()
	if d.channels == nil {
		d.channels = make(chan struct{}, d.config.SSHMaxChannels)
	}
	channels := d.channels
	d.mu.Unlock()
	select {
	case channels <- struct{}{}:
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	release := true
	defer func() {
		if release {
			<-channels
		}
	}()
	client, err := d.session(ctx)
	if err != nil {
		recordConnectionOutcome(false)
		report(d.reporter, "event=ssh_session result=failed detail=%s", err)
		return nil, err
	}
	started := time.Now()
	dialContext, cancelDial := context.WithTimeout(ctx, 20*time.Second)
	conn, err := client.DialContext(dialContext, "tcp", target)
	cancelDial()
	if err != nil {
		d.invalidate()
		report(d.reporter, "event=connection mode=ssh stage=direct_tcpip result=failed reason=%s", errorClass(err))
		recordConnectionOutcome(false)
		return nil, fmt.Errorf("SSH direct-tcpip: %w", err)
	}
	recordConnectionOutcome(true)
	report(d.reporter, "event=connection mode=ssh stage=direct_tcpip result=success elapsed_ms=%d", time.Since(started).Milliseconds())
	release = false
	tracked := &sshTrackedConn{Conn: conn, release: func() { <-channels }, bytes: &d.sessionBytes}
	return &diagnosticConn{Conn: tracked, connectionID: nextDiagnosticConnectionID(), reporter: d.reporter}, nil
}

func (d *sshDialer) session(ctx context.Context) (*ssh.Client, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.client != nil {
		return d.client, nil
	}
	if d.config.Type == "SSH_JUMP" {
		raw, err := d.protectedDialer().DialContext(ctx, "tcp", d.config.jumpAddress())
		if err != nil {
			return nil, fmt.Errorf("dial jump host: %w", err)
		}
		jump, err := newSSHClient(ctx, raw, d.config.JumpHost, d.config.JumpUsername, d.config.JumpPassword, d.config.JumpPrivateKey, d.config.JumpTrustedHostKey, d.config.JumpAcceptAnyHostKey, d.config.SSHProfile, d.config.SSHAuthMode, "jump", d.reporter)
		if err != nil {
			raw.Close()
			return nil, err
		}
		nested, err := jump.DialContext(ctx, "tcp", d.config.address())
		if err != nil {
			jump.Close()
			return nil, fmt.Errorf("jump host could not reach destination SSH host: %w", err)
		}
		client, err := newSSHClient(ctx, nested, d.config.Host, d.config.Username, d.config.Password, d.config.PrivateKey, d.config.TrustedHostKey, d.config.AcceptAnyHostKey, d.config.SSHProfile, d.config.SSHAuthMode, "destination", d.reporter)
		if err != nil {
			nested.Close()
			jump.Close()
			return nil, err
		}
		d.jumpClient, d.client = jump, client
	} else {
		raw, err := d.protectedDialer().DialContext(ctx, "tcp", d.config.address())
		if err != nil {
			return nil, fmt.Errorf("dial SSH host: %w", err)
		}
		client, err := newSSHClient(ctx, raw, d.config.Host, d.config.Username, d.config.Password, d.config.PrivateKey, d.config.TrustedHostKey, d.config.AcceptAnyHostKey, d.config.SSHProfile, d.config.SSHAuthMode, "destination", d.reporter)
		if err != nil {
			raw.Close()
			return nil, err
		}
		d.client = client
	}
	report(d.reporter, "event=ssh_session result=established type=%s profile=%s", d.config.Type, d.config.SSHProfile)
	d.sessionCreated = time.Now()
	d.sessionBytes.Store(0)
	d.startKeepalive()
	return d.client, nil
}

func newSSHClient(ctx context.Context, conn net.Conn, hostname, username, password, privateKey, trusted string, acceptAny bool, profile, authMode, hop string, reporter Reporter) (*ssh.Client, error) {
	auth, err := sshAuthMethods(privateKey, password, authMode)
	if err != nil {
		return nil, err
	}
	cfg := &ssh.ClientConfig{User: username, Auth: auth, HostKeyCallback: hostKeyCallback(trusted, acceptAny, hop), Timeout: 20 * time.Second}
	applySSHProfile(cfg, profile)
	type handshakeResult struct {
		conn     ssh.Conn
		channels <-chan ssh.NewChannel
		requests <-chan *ssh.Request
		err      error
	}
	result := make(chan handshakeResult, 1)
	go func() {
		cc, channels, requests, err := ssh.NewClientConn(conn, net.JoinHostPort(hostname, "22"), cfg)
		result <- handshakeResult{conn: cc, channels: channels, requests: requests, err: err}
	}()
	timer := time.NewTimer(20 * time.Second)
	defer timer.Stop()
	var handshake handshakeResult
	select {
	case handshake = <-result:
	case <-ctx.Done():
		conn.Close()
		<-result
		return nil, fmt.Errorf("SSH %s handshake: %w", hop, ctx.Err())
	case <-timer.C:
		conn.Close()
		<-result
		return nil, fmt.Errorf("SSH %s handshake: timeout", hop)
	}
	cc, channels, requests, err := handshake.conn, handshake.channels, handshake.requests, handshake.err
	if err != nil {
		return nil, fmt.Errorf("SSH %s handshake: %w", hop, err)
	}
	report(reporter, "event=ssh_handshake hop=%s result=success profile=%s", hop, profile)
	report(reporter, "event=transport_capability transport=ssh hop=%s client_family=%s server_family=%s direct_tcpip=true multiplexed=true rekey_mb=%d", hop, sshClientFamily(profile), sshServerFamily(string(cc.ServerVersion())), sshRekeyBytes/(1024*1024))
	return ssh.NewClient(cc, channels, requests), nil
}

func sshAuthMethods(privateKey, password, authMode string) ([]ssh.AuthMethod, error) {
	methods := make([]ssh.AuthMethod, 0, 2)
	if authMode != "PASSWORD_ONLY" && strings.TrimSpace(privateKey) != "" {
		signer, err := ssh.ParsePrivateKey([]byte(privateKey))
		if err != nil {
			var passphraseErr *ssh.PassphraseMissingError
			if errors.As(err, &passphraseErr) {
				return nil, errors.New("passphrase-protected SSH private keys are not supported")
			}
			return nil, fmt.Errorf("invalid SSH private key: %w", err)
		}
		methods = append(methods, ssh.PublicKeys(signer))
	}
	if authMode != "KEY_ONLY" && password != "" {
		methods = append(methods, ssh.Password(password))
		methods = append(methods, ssh.KeyboardInteractive(func(_ string, _ string, questions []string, _ []bool) ([]string, error) {
			answers := make([]string, len(questions))
			for i := range answers {
				answers[i] = password
			}
			return answers, nil
		}))
	}
	return methods, nil
}

func hostKeyCallback(trusted string, acceptAny bool, hop string) ssh.HostKeyCallback {
	return func(_ string, _ net.Addr, key ssh.PublicKey) error {
		fingerprint := ssh.FingerprintSHA256(key)
		if acceptAny || trusted == fingerprint {
			return nil
		}
		if trusted == "" {
			return fmt.Errorf("SSH_HOST_KEY_UNKNOWN|%s|%s|%s", hop, key.Type(), fingerprint)
		}
		return fmt.Errorf("SSH_HOST_KEY_CHANGED|%s|%s|%s|%s", hop, key.Type(), trusted, fingerprint)
	}
}

func applySSHProfile(c *ssh.ClientConfig, profile string) {
	switch profile {
	case "CONNECTBOT":
		c.ClientVersion = "SSH-2.0-ConnectBot"
	case "JUICESSH":
		c.ClientVersion = "SSH-2.0-JuiceSSH"
	case "TERMIUS_ANDROID":
		c.ClientVersion = "SSH-2.0-Termius"
	default:
		// OpenSSH 8.4 is a better claim than a newer banner whose mandatory/default
		// post-quantum proposal cannot be reproduced byte-for-byte by this stack.
		c.ClientVersion = "SSH-2.0-OpenSSH_8.4"
	}
	// This is the common modern OpenSSH-style order supported by x/crypto. It
	// avoids the distinctive Go defaults and weak legacy algorithms while keeping
	// compatibility with ordinary servers and dynamic forwarding (`ssh -D`).
	c.Config.KeyExchanges = []string{
		ssh.KeyExchangeCurve25519,
		ssh.KeyExchangeECDHP256,
		ssh.KeyExchangeECDHP384,
		ssh.KeyExchangeECDHP521,
		ssh.KeyExchangeDHGEXSHA256,
		ssh.KeyExchangeDH16SHA512,
		ssh.KeyExchangeDH14SHA256,
	}
	c.Config.Ciphers = []string{"chacha20-poly1305@openssh.com", "aes128-gcm@openssh.com", "aes256-gcm@openssh.com", "aes128-ctr", "aes192-ctr", "aes256-ctr"}
	c.Config.MACs = []string{ssh.HMACSHA256ETM, ssh.HMACSHA512ETM, ssh.HMACSHA256, ssh.HMACSHA512}
	c.Config.RekeyThreshold = sshRekeyBytes
}

const sshRekeyBytes = 1 << 30

func sshClientFamily(profile string) string {
	switch profile {
	case "CONNECTBOT":
		return "connectbot"
	case "JUICESSH":
		return "juicessh"
	case "TERMIUS_ANDROID":
		return "termius"
	default:
		return "openssh"
	}
}

func sshServerFamily(version string) string {
	value := strings.ToLower(version)
	switch {
	case strings.Contains(value, "openssh"):
		return "openssh"
	case strings.Contains(value, "dropbear"):
		return "dropbear"
	default:
		return "other"
	}
}

func (d *sshDialer) protectedDialer() *net.Dialer {
	return &net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second, Control: func(_, _ string, raw syscall.RawConn) error {
		var protectErr error
		if err := raw.Control(func(fd uintptr) {
			if !d.protector.Protect(int(fd)) {
				protectErr = errors.New("VpnService.protect rejected upstream socket")
			}
		}); err != nil {
			return err
		}
		return protectErr
	}}
}

func (d *sshDialer) DialUDP(metadata *M.Metadata) (net.PacketConn, error) {
	if metadata.DstPort != 53 {
		return nil, errUDPBlocked
	}
	client, limiter := d.sharedDoHResources()
	return newDoHPacketConnWithClient(d.config, d.reporter, d.connectTarget, d.config.DoHURL, client, limiter), nil
}

func (d *sshDialer) sharedDoHResources() (*http.Client, chan struct{}) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.dohClient == nil {
		d.dohClient = newDoHHTTPClient(d.connectTarget)
	}
	if d.dohInFlight == nil {
		d.dohInFlight = make(chan struct{}, 8)
	}
	return d.dohClient, d.dohInFlight
}

func (d *sshDialer) invalidate() {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.client != nil {
		d.client.Close()
	}
	if d.jumpClient != nil {
		d.jumpClient.Close()
	}
	d.client, d.jumpClient = nil, nil
	if d.keepaliveStop != nil {
		close(d.keepaliveStop)
		d.keepaliveStop = nil
	}
}

func (d *sshDialer) Close() error {
	d.invalidate()
	d.mu.Lock()
	if d.dohClient != nil {
		d.dohClient.CloseIdleConnections()
		d.dohClient = nil
	}
	d.mu.Unlock()
	return nil
}

func (d *sshDialer) rotationDue() bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.client == nil {
		return false
	}
	if d.channels != nil && len(d.channels) > 0 {
		return false
	}
	if d.config.SSHRotationMinutes > 0 && time.Since(d.sessionCreated) >= time.Duration(d.config.SSHRotationMinutes)*time.Minute {
		return true
	}
	return d.config.SSHRotationMB > 0 && d.sessionBytes.Load() >= uint64(d.config.SSHRotationMB)*1024*1024
}

func (d *sshDialer) startKeepalive() {
	if d.config.SSHKeepaliveSeconds <= 0 || d.client == nil {
		return
	}
	stop := make(chan struct{})
	d.keepaliveStop = stop
	client := d.client
	interval := time.Duration(d.config.SSHKeepaliveSeconds) * time.Second
	go func() {
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				if _, _, err := client.SendRequest("keepalive@openssh.com", true, nil); err != nil {
					return
				}
			case <-stop:
				return
			}
		}
	}()
}

type sshTrackedConn struct {
	net.Conn
	release func()
	bytes   *atomic.Uint64
	once    sync.Once
}

func (c *sshTrackedConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	c.bytes.Add(uint64(n))
	return n, err
}
func (c *sshTrackedConn) Write(p []byte) (int, error) {
	n, err := c.Conn.Write(p)
	c.bytes.Add(uint64(n))
	return n, err
}
func (c *sshTrackedConn) Close() error { err := c.Conn.Close(); c.once.Do(c.release); return err }
