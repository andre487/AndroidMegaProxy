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
    fun removesQuicIdentifiersAuthorizationHeadersAndPrivateKeys() {
        val raw = "Authorization: Basic dXNlcjpwYXNz Proxy-Authorization=Bearer hidden-token " +
            "username=\"private user\" password='private password' " +
            "masque://user:password@private.example:443 ssh://private-user@jump.example " +
            "ip=2001:db8::1 scoped=fe80::abcd%wlan0 mapped=::ffff:192.0.2.10 " +
            "fingerprint=SHA256:privateHostKey " +
            "-----BEGIN PRIVATE KEY-----\nprivate-key-payload\n-----END PRIVATE KEY-----\nSSH_HOST_KEY_CHANGED|destination|ssh-ed25519|privateInvalidPin|SHA256:newPin"
        val sanitized = PrivacyLogSanitizer.sanitize(raw)
        listOf("dXNlcjpwYXNz", "hidden-token", "private user", "private password", "user:password", "private-user",
            "2001:db8", "fe80", "wlan0", "192.0.2.10", "privateHostKey", "privateInvalidPin", "newPin", "private-key-payload")
            .forEach { assertFalse("Private value leaked: $it", sanitized.contains(it)) }
        assertTrue(sanitized.contains("[private_key]"))
    }

    @Test
    fun nativeFailuresExposeReasonsWithoutPeerMessages() {
        for ((raw, reason) in listOf(
            "x509 certificate signed by authority Private Company CA" to "certificate",
            "MASQUE proxy authentication failed (status 407): private-user" to "authentication",
            "timeout contacting private-server" to "timeout",
            "private-peer-message" to "other",
        )) assertEquals(reason, nativeFailureReason(raw))
    }

    @Test
    fun boundsRepeatedEventsWithoutLosingNewFailuresOrSessionEvents() {
        val limiter = DiagnosticLogLimiter()
        val base = "event=connection protocol=http3 stage=tunnel result=established"
        val messages = (1..1000).mapNotNull { limiter.filter("$base conn=$it", 0) }
        assertEquals(20, messages.size)
        val failure = "event=connection protocol=http3 stage=connect_response result=rejected status=407 reason=proxy_authentication"
        assertEquals(failure, limiter.filter(failure, 0))
        assertEquals("$base suppressed=980", limiter.filter(base, 10_000))
        repeat(100) { assertEquals("event=masque_session result=draining reason=goaway", limiter.filter("event=masque_session result=draining reason=goaway", 0)) }
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
