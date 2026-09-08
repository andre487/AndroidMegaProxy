package net.megaproxy487

import net.megaproxy487.data.ConfigWriteStatus
import net.megaproxy487.vpn.TestState
import net.megaproxy487.vpn.VpnConnectionState

/** Persistence only gates starting a connection; stopping must remain available. */
internal fun connectionActionEnabled(
    connection: VpnConnectionState,
    alwaysOn: Boolean,
    writes: ConfigWriteStatus,
    validProfile: Boolean,
): Boolean = !alwaysOn && (connection != VpnConnectionState.DISCONNECTED ||
    (writes.pending == 0 && !writes.failed && validProfile))

/** Returning to an in-flight diagnostic attaches to it instead of resetting its log. */
internal fun shouldAutoStartConnectionTest(state: TestState): Boolean = state != TestState.RUNNING
