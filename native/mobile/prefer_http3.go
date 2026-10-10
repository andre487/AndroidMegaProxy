package mobile

import (
	"context"
	"errors"
	"time"

	quic "github.com/refraction-networking/uquic"
)

type diagnosticFunc func(string)

func (f diagnosticFunc) Report(message string) { f(message) }

// Select once per VPN/test session. Established streams never change transport.
// A nil dialer means HTTPS; errors are terminal and must not trigger downgrade.
func preferredHTTP3(ctx context.Context, c config, protector Protector, reporter Reporter, stats *connectionStats) (*masqueDialer, error) {
	if !c.isHTTPS() || !c.PreferHTTP3 {
		return nil, nil
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	fallback := func(reason string) (*masqueDialer, error) {
		report(reporter, "event=transport_selection preferred=http3 selected=https result=fallback reason=%s udp=false", reason)
		return nil, nil
	}
	if _, err := c.quicSpec(); err != nil {
		return fallback("unsupported_fingerprint")
	}
	// Keep failed optional probes out of blocking/reconnect detection. Replay
	// successful negotiation details only after committing to HTTP/3.
	var messages []string
	d := &masqueDialer{config: c, protector: protector, stats: stats, reporter: diagnosticFunc(func(message string) {
		messages = append(messages, message)
	})}
	probe, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	if c.Type == "HTTPS_JUMP" {
		if c.Profile == "FIREFOX_ANDROID" {
			return fallback("unsupported_datagram_size")
		}
		jumpConfig := c
		jumpConfig.Type = "MASQUE"
		jumpConfig.Host, jumpConfig.DialHost, jumpConfig.Port = c.JumpHost, c.JumpDialHost, c.JumpPort
		jumpConfig.Username, jumpConfig.Password = c.JumpUsername, c.JumpPassword
		if c.SameJumpAuthentication {
			jumpConfig.Username, jumpConfig.Password = c.Username, c.Password
		}
		jumpConfig.AllowInvalidProxyCertificate = c.JumpAllowInvalidProxyCertificate
		jumpConfig.BypassLocalNetworks = false
		d.jump = &masqueDialer{config: jumpConfig, protector: protector, reporter: d.reporter, probePeerMTU: true}
		s, err := d.jump.getSession(probe)
		if err != nil {
			_ = d.Close()
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			return preferredHTTP3Failure(err, reporter, fallback)
		}
		if s.maxDatagramFrameSize < 1250 {
			_ = d.Close()
			return fallback("unsupported_datagram_size")
		}
	}
	_, err := d.getSession(probe)
	cancel()
	if err == nil && ctx.Err() != nil {
		_ = d.Close()
		return nil, ctx.Err()
	}
	if err == nil {
		d.reporter = reporter
		if d.jump != nil {
			d.jump.reporter = reporter
		}
		for _, message := range messages {
			report(reporter, "%s", message)
		}
		report(reporter, "event=transport_selection preferred=http3 selected=http3 result=selected udp=true")
		return d, nil
	}
	_ = d.Close()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	return preferredHTTP3Failure(err, reporter, fallback)
}

func preferredHTTP3Failure(err error, reporter Reporter, fallback func(string) (*masqueDialer, error)) (*masqueDialer, error) {
	reason := errorClass(err)
	var transportError *quic.TransportError
	if errors.As(err, &transportError) && transportError.ErrorCode == 0x178 { // TLS no_application_protocol
		reason = "unsupported_protocol"
	}
	switch reason {
	case "timeout", "refused", "unreachable", "reset", "eof", "unsupported_server_settings", "unsupported_protocol":
		return fallback(reason)
	default:
		// Certificate, authentication, socket protection and unknown failures
		// remain visible. Never reinterpret them as optional H3 availability.
		report(reporter, "event=transport_selection preferred=http3 result=failed reason=%s", reason)
		return nil, err
	}
}
