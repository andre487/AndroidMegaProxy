package net.megaproxy487.vpn

import android.app.Application
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.model.ProxyConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BlockingRecoveryTest {
    @After fun cleanup() {
        running(false)
        VpnRuntimeState.updateNetworkWarning(null)
    }

    private fun running(value: Boolean) {
        ProxyVpnService::class.java.getDeclaredField("isRunning")
            .apply { isAccessible = true }.setBoolean(null, value)
    }

    private fun set(service: ProxyVpnService, field: String, value: Any?) {
        ProxyVpnService::class.java.getDeclaredField(field).apply { isAccessible = true }.set(service, value)
    }

    private fun recover(service: ProxyVpnService, profile: String = "active") {
        ProxyVpnService::class.java.getDeclaredMethod("handleRuntimeDiagnostic", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(service, profile,
                "event=connection protocol=http3 stage=tunnel result=established quic_session=123")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun service(warning: String): ProxyVpnService {
        val service = Robolectric.buildService(ProxyVpnService::class.java).get()
        set(service, "activeSession", ConnectionTestTarget("active", ProxyConfig()))
        set(service, "blockingWarning", warning)
        running(true)
        VpnRuntimeState.updateNetworkWarning(warning)
        shadowOf(Looper.getMainLooper()).idle()
        return service
    }

    @Test fun workingTunnelClearsStaleBlockingWarningAndExhaustedFailoverNotice() {
        val warning = "Possible blocking; no alternative profiles"
        val service = service(warning)
        val store = ConfigStore(RuntimeEnvironment.getApplication())
        store.setFailoverState(false, warning)
        set(service, "failoverNotice", warning)
        recover(service)
        assertNull(VpnRuntimeState.networkWarning.value)
        assertNull(store.failoverNotice())
    }

    @Test fun successPreservesOtherWarningsAndActualFailoverNotice() {
        val service = service("Possible blocking")
        set(service, "failoverNotice", "Using another profile")
        VpnRuntimeState.updateNetworkWarning("Using another profile")
        recover(service)
        assertEquals("Using another profile", VpnRuntimeState.networkWarning.value)
    }

    @Test fun stoppedVpnAndOtherProfileCannotClearActiveWarning() {
        val warning = "Possible blocking"
        val service = service(warning)
        recover(service, "obsolete")
        assertEquals(warning, VpnRuntimeState.networkWarning.value)
        running(false)
        recover(service)
        assertEquals(warning, VpnRuntimeState.networkWarning.value)
    }
}
