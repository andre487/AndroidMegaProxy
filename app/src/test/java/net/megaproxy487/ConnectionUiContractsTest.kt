package net.megaproxy487

import net.megaproxy487.data.ConfigWriteStatus
import net.megaproxy487.vpn.TestState
import net.megaproxy487.vpn.VpnConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionUiContractsTest {
    @Test fun failedOrPendingSaveCannotTrapUserInVpn() {
        for (connection in listOf(VpnConnectionState.CONNECTED, VpnConnectionState.CONNECTING)) {
            assertTrue(connectionActionEnabled(connection, false, ConfigWriteStatus(1, true), false))
            assertFalse(connectionActionEnabled(connection, true, ConfigWriteStatus(), true))
        }
        assertFalse(connectionActionEnabled(VpnConnectionState.DISCONNECTED, false, ConfigWriteStatus(1), true))
        assertFalse(connectionActionEnabled(VpnConnectionState.DISCONNECTED, false, ConfigWriteStatus(failed = true), true))
        assertTrue(connectionActionEnabled(VpnConnectionState.DISCONNECTED, false, ConfigWriteStatus(), true))
    }

    @Test fun reopeningRunningDiagnosticDoesNotLaunchAnotherOne() {
        assertFalse(shouldAutoStartConnectionTest(TestState.RUNNING))
        assertTrue(shouldAutoStartConnectionTest(TestState.IDLE))
        assertTrue(shouldAutoStartConnectionTest(TestState.FAILED))
        assertTrue(shouldAutoStartConnectionTest(TestState.SUCCEEDED))
    }
}
