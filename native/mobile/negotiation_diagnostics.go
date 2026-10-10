package mobile

import (
	"crypto/tls"
	"fmt"
	"strings"

	"golang.org/x/crypto/ssh"
)

// Only locally defined protocol names and scalar state enter these messages.
// Never format ConnectionState/ConnMetadata, certificates, addresses or banners.
func tlsNegotiationDetails(version, cipher uint16, alpn string, resumed bool) string {
	return fmt.Sprintf("tls_version=%s cipher=%s cipher_id=0x%04x alpn=%s session_resumed=%t",
		strings.ReplaceAll(tls.VersionName(version), " ", ""), tls.CipherSuiteName(cipher), cipher, normalizedALPN(alpn), resumed)
}

func normalizedALPN(value string) string {
	switch value {
	case "":
		return "none"
	case "h2", "http/1.1", "h3":
		return value
	default:
		return "other"
	}
}

// SSH names may contain vendor domains. Emit domain-free aliases so the Android
// privacy sanitizer preserves useful algorithms without exempting arbitrary text.
var sshLogAlgorithms = func() map[string]string {
	names := make(map[string]string)
	aliases := strings.NewReplacer("@openssh.com", "_openssh", "@libssh.org", "_libssh", "@ssh.com", "_sshcom")
	for _, group := range []ssh.Algorithms{ssh.SupportedAlgorithms(), ssh.InsecureAlgorithms()} {
		for _, list := range [][]string{group.KeyExchanges, group.HostKeys, group.Ciphers, group.MACs} {
			for _, name := range list {
				alias := aliases.Replace(name)
				if !strings.ContainsAny(alias, "@.\r\n\t ") {
					names[name] = alias
				}
			}
		}
	}
	return names
}()

func sshLogAlgorithm(name string) string {
	if alias, ok := sshLogAlgorithms[name]; ok {
		return alias
	}
	return "unknown"
}

func sshLogMAC(direction ssh.DirectionAlgorithms) string {
	switch direction.Cipher {
	case ssh.CipherAES128GCM, ssh.CipherAES256GCM, ssh.CipherChaCha20Poly1305:
		return "aead"
	default:
		return sshLogAlgorithm(direction.MAC)
	}
}

func sshNegotiationDetails(a ssh.NegotiatedAlgorithms) string {
	return fmt.Sprintf("kex=%s host_key_algorithm=%s c2s_cipher=%s c2s_mac=%s s2c_cipher=%s s2c_mac=%s",
		sshLogAlgorithm(a.KeyExchange), sshLogAlgorithm(a.HostKey),
		sshLogAlgorithm(a.Write.Cipher), sshLogMAC(a.Write),
		sshLogAlgorithm(a.Read.Cipher), sshLogMAC(a.Read))
}
