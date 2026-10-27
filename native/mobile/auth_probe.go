package mobile

import (
	"context"
	"errors"
	"net"
	"net/http"
	"regexp"
	"strings"
	"time"
)

var authProbeLabel = regexp.MustCompile(`^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?$`)

func validAuthProbeHost(host string) bool {
	if host == "" {
		return true
	}
	if strings.HasPrefix(host, "[") != strings.HasSuffix(host, "]") {
		return false
	}
	if net.ParseIP(strings.TrimSuffix(strings.TrimPrefix(host, "["), "]")) != nil {
		return true
	}
	if len(host) > 253 {
		return false
	}
	for _, label := range strings.Split(host, ".") {
		if len(label) > 63 || !authProbeLabel.MatchString(label) {
			return false
		}
	}
	return true
}

// Only a configured, unmasked 407 proves an authentication rejection. Neither
// a timeout nor a 200 response proves that a decoy is (or is not) in use.
func checkProxyAuthentication(ctx context.Context, host string, connect func(context.Context, string) (net.Conn, error), reporter Reporter) error {
	if host == "" {
		report(reporter, "event=proxy_auth_probe result=skipped reason=not_configured")
		return nil
	}
	report(reporter, "event=proxy_auth_probe result=started timeout_ms=5000")
	started := time.Now()
	probeCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	connection, err := connect(probeCtx, net.JoinHostPort(strings.TrimSuffix(strings.TrimPrefix(host, "["), "]"), "443"))
	if connection != nil {
		// HTTP/1 TLS close_notify must not extend the bounded probe; HTTP/2
		// deadlines affect only this stream and preserve the shared session.
		_ = connection.SetDeadline(time.Now())
		_ = connection.Close()
	}
	var rejected *httpConnectStatusError
	if errors.As(err, &rejected) && rejected.status == http.StatusProxyAuthRequired {
		report(reporter, "event=proxy_auth_probe result=rejected reason=proxy_authentication status=407 elapsed_ms=%d", time.Since(started).Milliseconds())
		return err
	}
	if err != nil {
		status := 0
		if rejected != nil {
			status = rejected.status
		}
		report(reporter, "event=proxy_auth_probe result=inconclusive reason=%s status=%d elapsed_ms=%d", errorClass(err), status, time.Since(started).Milliseconds())
		return nil
	}
	report(reporter, "event=proxy_auth_probe result=connect_accepted authentication=unconfirmed status=200 elapsed_ms=%d", time.Since(started).Milliseconds())
	return nil
}
