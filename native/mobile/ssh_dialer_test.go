package mobile

import (
	"crypto/ed25519"
	"crypto/rand"
	"strings"
	"testing"

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
