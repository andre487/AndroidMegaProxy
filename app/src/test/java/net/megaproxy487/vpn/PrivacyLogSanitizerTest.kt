package net.megaproxy487.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyLogSanitizerTest {
    @Test
    fun removesNetworkIdentifiersCredentialsAndPackageNames() {
        val sanitized = PrivacyLogSanitizer.sanitize(
            "username=alice password=secret url=https://proxy.example.com/path " +
                "ipv4=192.0.2.10:443 ipv6=[2001:db8::1]:443 package=com.example.privateapp " +
                "email=user@example.com mac=00:11:22:33:44:55 path=/data/user/0/private/file",
        )

        listOf("alice", "secret", "proxy.example.com", "192.0.2.10", "2001:db8", "com.example.privateapp", "user@example.com", "00:11:22:33:44:55", "/data/user")
            .forEach { assertFalse(sanitized.contains(it)) }
        assertTrue(sanitized.contains("username=[redacted]"))
        assertTrue(sanitized.contains("password=[redacted]"))
        assertTrue(sanitized.contains("[url]"))
        assertTrue(sanitized.contains("[ip]"))
        assertTrue(sanitized.contains("[host]") || sanitized.contains("[package]"))
    }

    @Test
    fun keepsStructuredDiagnosticsAndFlattensLines() {
        val sanitized = PrivacyLogSanitizer.sanitize(
            "event=connection conn=42 stage=tls_handshake\nresult=failed reason=reset dpi_hint=possible_tls_interference",
        )

        assertFalse(sanitized.contains('\n'))
        assertTrue(sanitized.contains("event=connection"))
        assertTrue(sanitized.contains("dpi_hint=possible_tls_interference"))
    }
    @Test
    fun preservesSafeNegotiationFieldsWithoutExemptingHostnames() {
        val details = "tls_version=TLS1.3 cipher=TLS_AES_128_GCM_SHA256 " +
            "alpn=h2 http_version=HTTP/2 session_resumed=true " +
            "kex=curve25519-sha256 host_key_algorithm=ssh-ed25519 " +
            "c2s_cipher=chacha20-poly1305_openssh c2s_mac=aead " +
            "s2c_cipher=aes128-ctr s2c_mac=hmac-sha2-256-etm_openssh"
        assertEquals(details, PrivacyLogSanitizer.sanitize(details))
        val unsafe = PrivacyLogSanitizer.sanitize("alpn=private.example cipher=private.example username=alice password=secret")
        listOf("private.example", "alice", "secret").forEach { assertFalse(unsafe.contains(it)) }
    }
}
