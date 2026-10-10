package net.megaproxy487.model

import net.megaproxy487.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyConfigTest {
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
        for (url in listOf("https://dns.example/", "https://dns.example:8443/query?mode=1")) {
            assertNull(validConnection.copy(dnsProvider = DnsProvider.CUSTOM, customDohUrl = url).validationError())
        }
        for (url in listOf("http://dns.example/query", "https://user:secret@dns.example/query",
            "https://dns.example/query#fragment", "https://dns.example:65536/query", "https://dns.example")) {
            assertEquals(url, R.string.validation_doh_url,
                validConnection.copy(dnsProvider = DnsProvider.CUSTOM, customDohUrl = url).validationError())
        }
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
