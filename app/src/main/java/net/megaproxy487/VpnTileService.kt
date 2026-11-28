package net.megaproxy487

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigWrites
import net.megaproxy487.data.operationResult
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.VpnConnectionState
import net.megaproxy487.vpn.VpnRuntimeState
import net.megaproxy487.vpn.hasOtherProvider
import net.megaproxy487.vpn.readAlwaysOnVpnStatus

class VpnTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null
    private var click: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            val systemAlwaysOn = withContext(ConfigIoDispatcher) { readAlwaysOnVpnStatus(this@VpnTileService).enabled }
            snapshotFlow { VpnRuntimeState.connection.value to VpnRuntimeState.alwaysOn.value }
                .collect { (connection, alwaysOn) -> updateTile(connection, systemAlwaysOn || alwaysOn) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        if (click?.isActive == true) return
        click = scope.launch {
            val status = withContext(ConfigIoDispatcher) { readAlwaysOnVpnStatus(this@VpnTileService) }
            val connection = VpnRuntimeState.connection.value
            if (status.enabled || VpnRuntimeState.alwaysOn.value) {
                updateTile(connection, alwaysOn = true)
                return@launch
            }
            if (connection != VpnConnectionState.DISCONNECTED) {
                ProxyVpnService.stop(this@VpnTileService)
                return@launch
            }
            val ready = withContext(ConfigIoDispatcher) {
                operationResult {
                    val store = ConfigStore(this@VpnTileService)
                    val config = store.globalConnectionSettings().applyTo(store.activeProfile().config)
                    !status.hasOtherProvider && VpnService.prepare(this@VpnTileService) == null &&
                        config.profileValidationError() == null &&
                        !config.needsSshPassphrase(false, requireSaved = true) &&
                        !config.needsSshPassphrase(true, requireSaved = true)
                }.getOrDefault(false)
            }
            val current = VpnRuntimeState.connection.value
            if (current != VpnConnectionState.DISCONNECTED || VpnRuntimeState.alwaysOn.value) return@launch
            if (connectionActionEnabled(current, false, ConfigWrites.status.value, ready)) {
                ProxyVpnService.start(this@VpnTileService)
            } else {
                openApp()
            }
        }
    }

    private fun updateTile(connection: VpnConnectionState, alwaysOn: Boolean) {
        val tile = qsTile ?: return
        tile.state = when {
            alwaysOn -> Tile.STATE_UNAVAILABLE
            connection == VpnConnectionState.DISCONNECTED -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        val label = uiText(when {
            alwaysOn -> R.string.always_on_badge
            connection == VpnConnectionState.CONNECTING -> R.string.status_connecting
            connection == VpnConnectionState.CONNECTED -> R.string.status_connected
            else -> R.string.status_disconnected
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = label
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
