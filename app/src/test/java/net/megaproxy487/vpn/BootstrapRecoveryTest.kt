package net.megaproxy487.vpn

import android.app.Application
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BootstrapRecoveryTest {
    @Test fun refreshOnlyProxyReachabilityFailuresWithoutBlockingWarning() {
        for (detail in listOf(
            "event=connection stage=tcp_connect result=failed reason=refused",
            "event=connection protocol=socks5 stage=socks_handshake result=failed reason=unreachable scope=proxy",
            "event=connection protocol=http3 stage=tls_handshake result=failed reason=refused",
            "event=ssh_transport stage=direct_tcpip result=failed reason=refused",
        )) {
            assertTrue(detail, isBootstrapConnectionFailure(detail))
            assertNull(detail, BlockingDetection.classify(detail))
        }
        for (detail in listOf(
            "event=connection stage=tcp_connect result=failed reason=refused mode=direct",
            "event=connection stage=tcp_connect result=failed reason=refused scope=target",
            "event=connection stage=tls_handshake result=failed reason=certificate",
            "event=connection stage=proxy_auth result=failed reason=auth",
            "event=connection stage=tcp_connect result=success reason=refused",
        )) assertFalse(detail, isBootstrapConnectionFailure(detail))
    }

    @Test fun staleAddressesAreRemovedButConcurrentFreshAddressIsPreserved() {
        val cache = BootstrapAddressCache(RuntimeEnvironment.getApplication())
        cache.put("proxy.example", "192.0.2.1")
        cache.put("jump.example", "192.0.2.2")
        val failed = ProxyConfig(type = ProxyType.HTTPS_JUMP, host = "proxy.example", jumpHost = "jump.example",
            resolvedProxyIp = "192.0.2.1", resolvedJumpIp = "192.0.2.2")
        cache.put("proxy.example", "192.0.2.3")
        assertTrue(cache.invalidateFailedAddresses(failed))
        assertEquals("192.0.2.3", cache.get("proxy.example"))
        assertNull(cache.get("jump.example"))
        assertFalse(cache.invalidateFailedAddresses(failed))
    }
    @Test fun dnsRefreshIsBoundedAndLateCallbacksCannotInvalidateAnotherSession() {
        val cache = BootstrapAddressCache(RuntimeEnvironment.getApplication())
        val config = ProxyConfig(host = "proxy.example", resolvedProxyIp = "192.0.2.1")
        val service = org.robolectric.Robolectric.buildService(ProxyVpnService::class.java).get()
        val refresh = ProxyVpnService::class.java.getDeclaredMethod("refreshFailedBootstrap", String::class.java, ProxyConfig::class.java)
            .apply { isAccessible = true }
        cache.put(config.host, config.resolvedProxyIp)
        assertEquals(true, refresh.invoke(service, "profile", config))
        cache.put(config.host, config.resolvedProxyIp)
        assertEquals(false, refresh.invoke(service, "profile", config))
        assertEquals(config.resolvedProxyIp, cache.get(config.host))
        ProxyVpnService::class.java.getDeclaredField("activeSession").apply { isAccessible = true }
            .set(service, ConnectionTestTarget("current", config))
        val running = ProxyVpnService::class.java.getDeclaredField("isRunning").apply { isAccessible = true }
        val runtime = ProxyVpnService::class.java.getDeclaredMethod("handleRuntimeDiagnostic", String::class.java, String::class.java)
            .apply { isAccessible = true }
        try {
            running.setBoolean(null, true)
            runtime.invoke(service, "obsolete", "stage=tcp_connect result=failed reason=refused")
            assertEquals(config.resolvedProxyIp, cache.get(config.host))
            running.setBoolean(null, false)
            runtime.invoke(service, "current", "stage=tcp_connect result=failed reason=refused")
            assertEquals(config.resolvedProxyIp, cache.get(config.host))
            assertNull(VpnRuntimeState.networkWarning.value)
        } finally { running.setBoolean(null, false) }
    }

}
