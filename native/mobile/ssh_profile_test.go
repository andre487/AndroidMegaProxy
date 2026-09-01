package mobile

import (
	"slices"
	"testing"

	"golang.org/x/crypto/ssh"
)

func TestOpenSSHProfileUsesOrdinaryDynamicForwardingFingerprint(t *testing.T) {
	c := &ssh.ClientConfig{}
	applySSHProfile(c, "OPENSSH_TERMUX")
	if c.ClientVersion != "SSH-2.0-OpenSSH_8.4" {
		t.Fatalf("unexpected client banner %q", c.ClientVersion)
	}
	if c.RekeyThreshold != sshRekeyBytes {
		t.Fatalf("unexpected rekey threshold %d", c.RekeyThreshold)
	}
	if len(c.KeyExchanges) < 5 || c.KeyExchanges[0] != ssh.KeyExchangeCurve25519 {
		t.Fatalf("unexpected KEX order %#v", c.KeyExchanges)
	}
	if !slices.Contains(c.Ciphers, ssh.CipherAES192CTR) {
		t.Fatalf("OpenSSH-compatible AES-192 CTR is missing: %#v", c.Ciphers)
	}
}

func TestSSHProfilesDoNotExposeImplementationName(t *testing.T) {
	for _, profile := range []string{"OPENSSH_TERMUX", "CONNECTBOT", "JUICESSH", "TERMIUS_ANDROID"} {
		c := &ssh.ClientConfig{}
		applySSHProfile(c, profile)
		if c.ClientVersion == "" || c.ClientVersion == "SSH-2.0-Go" {
			t.Fatalf("profile %s exposes an invalid implementation banner %q", profile, c.ClientVersion)
		}
	}
}
