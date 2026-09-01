package net.megaproxy487.model

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
    fun splitTunnelingAllowsNoApplications() {
        assertNull(validConnection.copy(routeAllApps = false).validationError())
    }

    @Test
    fun globalVpnDoesNotRequireSelectedApplications() {
        assertNull(validConnection.validationError())
    }
}
