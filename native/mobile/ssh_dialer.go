package mobile

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"

	M "github.com/xjasonlyu/tun2socks/v2/metadata"
	"golang.org/x/crypto/ssh"
)

// sshDialer implements OpenSSH-style dynamic forwarding. SOCKS CONNECT semantics
// are mapped directly to SSH direct-tcpip channels; SSH has no general UDP relay.
type sshDialer struct {
	config     config
	protector  Protector
	reporter   Reporter
	mu         sync.Mutex
	client     *ssh.Client
	jumpClient *ssh.Client
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
	client, err := d.session(ctx)
	if err != nil {
		recordConnectionOutcome(false)
		report(d.reporter, "event=ssh_session result=failed detail=%s", err)
		return nil, err
	}
	started := time.Now()
	conn, err := client.Dial("tcp", target)
	if err != nil {
		d.invalidate()
		report(d.reporter, "event=connection mode=ssh stage=direct_tcpip result=failed reason=%s", errorClass(err))
		recordConnectionOutcome(false)
		return nil, fmt.Errorf("SSH direct-tcpip: %w", err)
	}
	recordConnectionOutcome(true)
	report(d.reporter, "event=connection mode=ssh stage=direct_tcpip result=success elapsed_ms=%d", time.Since(started).Milliseconds())
	return &diagnosticConn{Conn: conn, connectionID: nextDiagnosticConnectionID(), reporter: d.reporter}, nil
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
		jump, err := newSSHClient(raw, d.config.JumpHost, d.config.JumpUsername, d.config.JumpPassword, d.config.JumpPrivateKey, d.config.JumpTrustedHostKey, d.config.JumpAcceptAnyHostKey, d.config.SSHProfile, "jump", d.reporter)
		if err != nil {
			raw.Close()
			return nil, err
		}
		nested, err := jump.Dial("tcp", d.config.address())
		if err != nil {
			jump.Close()
			return nil, fmt.Errorf("jump host could not reach destination SSH host: %w", err)
		}
		client, err := newSSHClient(nested, d.config.Host, d.config.Username, d.config.Password, d.config.PrivateKey, d.config.TrustedHostKey, d.config.AcceptAnyHostKey, d.config.SSHProfile, "destination", d.reporter)
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
		client, err := newSSHClient(raw, d.config.Host, d.config.Username, d.config.Password, d.config.PrivateKey, d.config.TrustedHostKey, d.config.AcceptAnyHostKey, d.config.SSHProfile, "destination", d.reporter)
		if err != nil {
			raw.Close()
			return nil, err
		}
		d.client = client
	}
	report(d.reporter, "event=ssh_session result=established type=%s profile=%s", d.config.Type, d.config.SSHProfile)
	return d.client, nil
}

func newSSHClient(conn net.Conn, hostname, username, password, privateKey, trusted string, acceptAny bool, profile, hop string, reporter Reporter) (*ssh.Client, error) {
	auth, err := sshAuthMethods(privateKey, password)
	if err != nil {
		return nil, err
	}
	cfg := &ssh.ClientConfig{User: username, Auth: auth, HostKeyCallback: hostKeyCallback(trusted, acceptAny, hop), Timeout: 20 * time.Second}
	applySSHProfile(cfg, profile)
	cc, channels, requests, err := ssh.NewClientConn(conn, net.JoinHostPort(hostname, "22"), cfg)
	if err != nil {
		return nil, fmt.Errorf("SSH %s handshake: %w", hop, err)
	}
	report(reporter, "event=ssh_handshake hop=%s result=success profile=%s", hop, profile)
	return ssh.NewClient(cc, channels, requests), nil
}

func sshAuthMethods(privateKey, password string) ([]ssh.AuthMethod, error) {
	methods := make([]ssh.AuthMethod, 0, 2)
	if strings.TrimSpace(privateKey) != "" {
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
	if password != "" {
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
		c.ClientVersion = "SSH-2.0-OpenSSH_9.9"
	}
	// x/crypto exposes ordering, but not every packet-level extension used by the
	// named clients. Keep modern algorithms only and use the preset for stable ordering/banner.
	c.Config.KeyExchanges = []string{ssh.KeyExchangeCurve25519, ssh.KeyExchangeECDHP256, ssh.KeyExchangeDHGEXSHA256}
	c.Config.Ciphers = []string{"chacha20-poly1305@openssh.com", "aes128-gcm@openssh.com", "aes256-gcm@openssh.com", "aes128-ctr", "aes256-ctr"}
	c.Config.MACs = []string{ssh.HMACSHA256ETM, ssh.HMACSHA512ETM, ssh.HMACSHA256, ssh.HMACSHA512}
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
	return newDoHPacketConn(d.config, d.reporter, d.connectTarget, d.config.DoHURL), nil
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
}

func (d *sshDialer) Close() error { d.invalidate(); return nil }
