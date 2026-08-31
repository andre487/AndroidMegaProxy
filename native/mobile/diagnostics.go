package mobile

import (
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync/atomic"
)

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
	if errors.Is(err, io.EOF) {
		return "eof"
	}
	if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
		return "timeout"
	}
	message := strings.ToLower(err.Error())
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
