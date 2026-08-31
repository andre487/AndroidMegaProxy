package dev.megaproxy.app.vpn

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

enum class VpnConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

object VpnRuntimeState {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableConnection = mutableStateOf(
        if (ProxyVpnService.isRunning) VpnConnectionState.CONNECTED else VpnConnectionState.DISCONNECTED,
    )
    val connection: State<VpnConnectionState> = mutableConnection

    fun update(value: VpnConnectionState) {
        if (Looper.myLooper() == Looper.getMainLooper()) mutableConnection.value = value
        else mainHandler.post { mutableConnection.value = value }
    }
}
