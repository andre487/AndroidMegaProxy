package net.megaproxy487.vpn

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
}
