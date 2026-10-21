package net.megaproxy487.model

import net.megaproxy487.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyConfigTest {
    @Test fun basicAuthRejectsColonInUsernameWhileSocksAndSshAllowIt() {
        for (type in ProxyType.entries) {
            val config = ProxyConfig(type = type, host = "proxy.example", port = type.defaultPort,
                username = "user:name", password = "synthetic:password", jumpHost = "jump.example")
            assertEquals(if (type.isHttpProxy) R.string.validation_basic_username_colon else null, config.validationError())
        }
        val jump = ProxyConfig(type = ProxyType.HTTPS_JUMP, host = "proxy.example", username = "user",
            password = "synthetic:password", jumpHost = "jump.example", sameJumpAuthentication = false,
            jumpUsername = "jump:user", jumpPassword = "synthetic")
        assertEquals(R.string.validation_basic_username_colon, jump.validationError())
        assertNull(jump.copy(jumpUsername = "jump-user").validationError())
    }

    @Test fun `SOCKS5 credentials are optional and checked by UTF-8 byte length`() {
        val base = ProxyConfig(type = ProxyType.SOCKS5, host = "proxy.example", port = 1080,
            profile = TlsProfile.CUSTOM, customJa3 = "ignored", sshAuthMode = SshAuthMode.KEY_ONLY)
        assertNull(base.validationError())
        assertNull(base.copy(username = "я".repeat(127), password = "p").validationError())
        assertEquals(R.string.validation_socks5_auth, base.copy(username = "я".repeat(128), password = "p").validationError())
        assertEquals(R.string.validation_socks5_auth, base.copy(username = "u").validationError())
        assertEquals(R.string.validation_socks5_auth, base.copy(password = "p").validationError())
        assertEquals(R.string.validation_socks5_auth, base.copy(username = "u", password = "p".repeat(256)).validationError())
        assertEquals(R.string.error_credentials_unavailable, base.copy(unreadableSecrets = mapOf("password" to "opaque")).validationError())
        assertNull(base.copy(unreadableSecrets = mapOf("privateKey" to "opaque")).validationError())
        assertFalse(ProxyType.SOCKS5.isSsh)
        assertFalse(ProxyType.SOCKS5.isHttpProxy)
        assertFalse(ProxyType.SOCKS5.hasJump)
    }

    @Test
    fun `MASQUE requires Basic credentials and QUIC compatible custom JA3`() {
        val config = ProxyConfig(type = ProxyType.MASQUE, host = "proxy.example", username = "user", password = "password")
        assertNull(config.validationError())
        assertEquals(R.string.validation_basic_password, config.copy(password = "").validationError())
        assertEquals(R.string.validation_quic_ja3, config.copy(profile = TlsProfile.CUSTOM,
            customJa3 = "771,4865,0-10-13-16-43-51,29,0").validationError())
        assertNull(config.copy(profile = TlsProfile.CUSTOM,
            customJa3 = "771,4865-4866-4867,0-10-13-16-43-51-57,29,0").validationError())
        assertTrue(ProxyType.MASQUE.isHttpProxy)
        assertFalse(ProxyType.MASQUE.hasJump)
    }

    @Test
    fun `mixed HTTPS and MASQUE failover preserves separate custom fingerprints`() {
        val tls = "771,4865-4866-4867,0-10-13-16-43-51,29,0"
        val quic = "771,4865-4866-4867,0-10-13-16-43-51-57,29,0"
        val settings = GlobalConnectionSettings(tlsProfile = TlsProfile.CUSTOM, customJa3 = tls)
        val masque = settings.applyTo(validConnection.copy(type = ProxyType.MASQUE, customJa3 = quic))
        val https = settings.applyTo(validConnection)
        assertEquals(quic, masque.customJa3)
        assertEquals(tls, https.customJa3)
        assertNull(masque.validationError())
        assertNull(https.validationError())
        assertNull(settings.applyTo(validConnection.copy(type = ProxyType.HTTPS_JUMP, jumpHost = "jump.example")).validationError())
        assertEquals(R.string.validation_ja3, settings.applyTo(masque.copy(customJa3 = "")).validationError())
    }

    @Test
    fun `default TLS fingerprint currently resolves to Chrome Android`() {
        val resolved = GlobalConnectionSettings(tlsProfile = TlsProfile.DEFAULT)
            .applyTo(ProxyConfig())

        assertEquals(TlsProfile.CHROME_ANDROID, resolved.profile)
    }

    @Test
    fun `global settings preserve profile IPv6 capability`() {
        val resolved = GlobalConnectionSettings().applyTo(ProxyConfig(allowIpv6 = true))

        assertEquals(true, resolved.allowIpv6)
    }

    private val validConnection = ProxyConfig(
        host = "proxy.example.com",
        username = "user",
        password = "password",
    )

    @Test
    fun `HTTPS jump validates both proxies and supports shared credentials`() {
        val chain = validConnection.copy(type = ProxyType.HTTPS_JUMP, jumpHost = "jump.example", jumpPort = 443)
        assertNull(chain.validationError())
        assertEquals(R.string.validation_jump_host, chain.copy(jumpHost = "").validationError())
        assertEquals(R.string.validation_jump_port, chain.copy(jumpPort = 0).validationError())
        assertEquals(R.string.validation_jump_basic_username, chain.copy(sameJumpAuthentication = false).validationError())
        assertEquals(R.string.validation_jump_basic_password, chain.copy(sameJumpAuthentication = false, jumpUsername = "jump").validationError())
        assertNull(chain.copy(sameJumpAuthentication = false, jumpUsername = "jump", jumpPassword = "secret").validationError())
        assertEquals(443, ProxyConfig(type = ProxyType.HTTPS_JUMP).jumpPort)
        assertEquals(22, ProxyConfig(type = ProxyType.SSH_JUMP).jumpPort)
    }

    @Test
    fun `proxy transport families include both jump modes`() {
        assertEquals(setOf(ProxyType.HTTPS, ProxyType.HTTPS_JUMP), ProxyType.entries.filter { it.isHttps }.toSet())
        assertEquals(setOf(ProxyType.SSH_JUMP, ProxyType.HTTPS_JUMP), ProxyType.entries.filter { it.hasJump }.toSet())
    }

    @Test
    fun customDnsUrlValidationMatchesNativeRequirements() {
        val validUrls = listOf(
            "https://dns.example", "https://dns.example/", "https://dns.example?key=synthetic",
            "HTTPS://dns.example/query", "https://dns.example:8443/query?mode=1",
            "https://192.0.2.1", "https://192.0.2.1:8443/dns-query",
            "https://[2001:db8::1]", "https://[2001:db8::1]:8443/dns-query",
        )
        for (type in ProxyType.entries) {
            val config = validConnection.copy(type = type, jumpHost = "jump.example", dnsProvider = DnsProvider.CUSTOM)
            for (url in validUrls) {
                assertNull("$type: $url", config.copy(customDohUrl = "  $url  ").validationError())
            }
            for (url in listOf("", "192.0.2.1", "2001:db8::1", "dns.example:53",
                "tls://dns.example", "http://dns.example/query")) {
                assertEquals("$type: $url", R.string.validation_doh_https_required,
                    config.copy(customDohUrl = url).validationError())
            }
            for (url in listOf("https:///query",
                "https://user:secret@dns.example/query", "https://dns.example/query#fragment",
                "https://dns.example:0/query", "https://dns.example:65536/query", "https://dns.example/bad path",
                "https://2001:db8::1/dns-query", "https://dns.example/#")) {
                assertEquals("$type: $url", R.string.validation_doh_url,
                    config.copy(customDohUrl = url).validationError())
            }
        }
        assertNull(validConnection.copy(customDohUrl = "invalid but unused").validationError())
    }

    @Test fun plainDnsRequiresLocalConsentAndNumericServer() {
        val base = validConnection.copy(dnsProvider = DnsProvider.CUSTOM)
        for (scheme in listOf("tcp", "udp")) {
            for (server in listOf("192.0.2.1", "192.0.2.1:5353", "[2001:db8::1]", "[::1]:5353")) {
                val config = base.copy(customDohUrl = "$scheme://$server")
                assertEquals(R.string.validation_insecure_dns_disabled, config.validationError())
                assertNull(config.copy(allowInsecureDns = true).validationError())
            }
            for (server in listOf("dns.example", "192.0.2.1:0", "192.0.2.1:65536", "2001:db8::1", "192.0.2.1/path", "192.0.2.1?x=1")) {
                assertEquals(server, R.string.validation_dns_server, base.copy(customDohUrl = "$scheme://$server", allowInsecureDns = true).validationError())
            }
        }
        assertFalse(base.allowInvalidDohCertificate)
        assertNull(base.copy(customDohUrl = "https://dns.example", allowInvalidDohCertificate = true).validationError())
    }

    @Test
    fun splitTunnelingAllowsNoApplications() {
        assertNull(validConnection.copy(routeAllApps = false).validationError())
    }

    @Test
    fun globalVpnDoesNotRequireSelectedApplications() {
        assertNull(validConnection.validationError())
    }
}
