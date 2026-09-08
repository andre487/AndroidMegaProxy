package net.megaproxy487.model

import net.megaproxy487.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyConfigTest {
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
