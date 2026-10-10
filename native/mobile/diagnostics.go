package mobile

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync/atomic"

	quic "github.com/refraction-networking/uquic"
	tunnelLog "github.com/xjasonlyu/tun2socks/v2/log"
	"go.uber.org/zap"
)

// tun2socks logs include private destinations and bypass the Android sanitizer.
// Production networking reports only our structured diagnostic events.
func init() { tunnelLog.SetLogger(zap.NewNop()) }

var diagnosticConnectionSequence atomic.Uint64

// Reporter is implemented by Android and receives sanitized diagnostic events.
type Reporter interface {
	Report(message string)
}

func nextDiagnosticConnectionID() uint64 { return diagnosticConnectionSequence.Add(1) }

func errorClass(err error) string {
	if err == nil {
		return "none"
	}
	if errors.Is(err, errMasqueSettings) {
		return "unsupported_server_settings"
	}
	if errors.Is(err, errMasqueCapsuleProtocol) {
		return "missing_capsule_protocol"
	}
	if errors.Is(err, context.Canceled) {
		return "canceled"
	}
	var stream *quic.StreamError
	if errors.As(err, &stream) {
		return "reset"
	}
	var rejected *masqueConnectError
	if errors.As(err, &rejected) {
		if rejected.status == 407 {
			return "proxy_authentication"
		}
		return "connect_rejected"
	}
	if errors.Is(err, io.EOF) {
		return "eof"
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return "timeout"
	}
	message := strings.ToLower(err.Error())
	if strings.Contains(message, "certificate") || strings.Contains(message, "x509") {
		return "certificate"
	}
	if errors.Is(err, net.ErrClosed) {
		return "closed"
	}
	switch {
	case strings.Contains(message, "reset"):
		return "reset"
	case strings.Contains(message, "refused"):
		return "refused"
	case strings.Contains(message, "unreachable"):
		return "unreachable"
	case strings.Contains(message, "certificate") || strings.Contains(message, "x509"):
		return "certificate"
	case strings.Contains(message, "tls") && strings.Contains(message, "alert"):
		return "tls_alert"
	case strings.Contains(message, "protect rejected"):
		return "vpn_protect"
	default:
		return "other"
	}
}

func tlsInterferenceHint(reason string) string {
	if reason == "reset" || reason == "eof" || reason == "timeout" {
		return "possible_tls_interference"
	}
	return "none"
}

func report(reporter Reporter, format string, args ...any) {
	if reporter != nil {
		reporter.Report(fmt.Sprintf(format, args...))
	}
}

// Peer error messages can contain arbitrary/private data. Only codes and direction
// are diagnostic; never forward ErrorMessage or QUIC wire connection IDs.
func quicErrorDetails(err error) string {
	var stream *quic.StreamError
	if errors.As(err, &stream) {
		return fmt.Sprintf("quic_error_kind=stream quic_error_code=%d remote=%t", stream.ErrorCode, stream.Remote)
	}
	var transport *quic.TransportError
	if errors.As(err, &transport) {
		return fmt.Sprintf("quic_error_kind=transport quic_error_code=%d remote=%t", transport.ErrorCode, transport.Remote)
	}
	var application *quic.ApplicationError
	if errors.As(err, &application) {
		return fmt.Sprintf("quic_error_kind=application quic_error_code=%d remote=%t", application.ErrorCode, application.Remote)
	}
	return ""
}

// Host-key decisions still reach the JNI status callback; Android strips pins
// before persistence. Other SSH failures must never forward raw peer text.
func sshFailureDetails(err error) string {
	text := err.Error()
	for _, marker := range []string{"SSH_HOST_KEY_UNKNOWN|", "SSH_HOST_KEY_CHANGED|"} {
		if index := strings.Index(text, marker); index >= 0 {
			return "detail=" + text[index:]
		}
	}
	return "reason=" + errorClass(err)
}
