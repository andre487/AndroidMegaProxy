package net.megaproxy487

import android.app.Application
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import net.megaproxy487.vpn.VpnConnectionState
import net.megaproxy487.vpn.VpnRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class VpnTileStateTest {
    @Test fun tileObserverReceivesChangesWithoutAnOpenActivity() = runBlocking {
        VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
        VpnRuntimeState.updateSystem(false, false, "")
        val states = mutableListOf<Pair<VpnConnectionState, Boolean>>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            snapshotFlow { VpnRuntimeState.connection.value to VpnRuntimeState.alwaysOn.value }
                .collect { states.add(it) }
        }
        try {
            VpnRuntimeState.update(VpnConnectionState.CONNECTING)
            yield()
            VpnRuntimeState.update(VpnConnectionState.CONNECTED)
            yield()
            VpnRuntimeState.updateSystem(true, false, "")
            yield()
            VpnRuntimeState.updateSystem(false, false, "")
            yield()
            VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
            yield()
            assertEquals(
                listOf(
                    VpnConnectionState.DISCONNECTED to false,
                    VpnConnectionState.CONNECTING to false,
                    VpnConnectionState.CONNECTED to false,
                    VpnConnectionState.CONNECTED to true,
                    VpnConnectionState.CONNECTED to false,
                    VpnConnectionState.DISCONNECTED to false,
                ),
                states,
            )
        } finally {
            observer.cancel()
            VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
            VpnRuntimeState.updateSystem(false, false, "")
        }
    }
}
