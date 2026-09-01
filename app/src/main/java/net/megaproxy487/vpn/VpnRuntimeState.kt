package net.megaproxy487.vpn

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
    private val mutableAlwaysOn = mutableStateOf(ProxyVpnService.isAlwaysOnMode)
    private val mutableLockdown = mutableStateOf(ProxyVpnService.isLockdownMode)
    private val mutableConnectionProfileId = mutableStateOf("")
    val alwaysOn: State<Boolean> = mutableAlwaysOn
    val lockdown: State<Boolean> = mutableLockdown
    val connectionProfileId: State<String> = mutableConnectionProfileId
    private val mutableNetworkWarning = mutableStateOf<String?>(null)
    val networkWarning: State<String?> = mutableNetworkWarning

    fun update(value: VpnConnectionState) {
        if (Looper.myLooper() == Looper.getMainLooper()) mutableConnection.value = value
        else mainHandler.post { mutableConnection.value = value }
    }

    fun updateNetworkWarning(value: String?) {
        if (Looper.myLooper() == Looper.getMainLooper()) mutableNetworkWarning.value = value
        else mainHandler.post { mutableNetworkWarning.value = value }
    }

    fun updateSystem(alwaysOn: Boolean, lockdown: Boolean, connectionProfileId: String) {
        val update = {
            mutableAlwaysOn.value = alwaysOn
            mutableLockdown.value = lockdown
            mutableConnectionProfileId.value = connectionProfileId
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update() else mainHandler.post(update)
    }
}
