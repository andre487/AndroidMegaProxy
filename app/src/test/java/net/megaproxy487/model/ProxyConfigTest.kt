package net.megaproxy487.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyConfigTest {
    private val validConnection = ProxyConfig(
        host = "proxy.example.com",
        username = "user",
        password = "password",
    )

    @Test
    fun splitTunnelingRequiresAnApplication() {
        assertEquals("Select at least one application", validConnection.validationError())
    }

    @Test
    fun globalVpnDoesNotRequireSelectedApplications() {
        assertNull(validConnection.copy(routeAllApps = true).validationError())
    }
}
