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
	if c.Type != "HTTPS" || !c.PreferHTTP3 {
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
	_, err := d.getSession(probe)
	cancel()
	if err == nil && ctx.Err() != nil {
		_ = d.Close()
		return nil, ctx.Err()
	}
	if err == nil {
		d.reporter = reporter
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
