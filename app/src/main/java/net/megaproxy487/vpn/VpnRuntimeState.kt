package net.megaproxy487.vpn

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

enum class VpnConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
enum class VpnTransportProtocol { UNKNOWN, HTTP_1_1, HTTP_2, SSH_MULTIPLEXED }

fun transportProtocolFromDiagnostic(message: String): VpnTransportProtocol? = when {
    "event=connection" in message && "protocol=http2" in message &&
        "stage=tunnel" in message && "result=established" in message -> VpnTransportProtocol.HTTP_2
    "event=connection" in message && "mode=proxy" in message &&
        "stage=tunnel" in message && "result=established" in message -> VpnTransportProtocol.HTTP_1_1
    "event=transport_capability" in message && "transport=ssh" in message &&
        "multiplexed=true" in message -> VpnTransportProtocol.SSH_MULTIPLEXED
    else -> null
}

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
    private val mutableTransportProtocol = mutableStateOf(VpnTransportProtocol.UNKNOWN)
    val transportProtocol: State<VpnTransportProtocol> = mutableTransportProtocol

    fun update(value: VpnConnectionState) {
        val update = {
            mutableConnection.value = value
            if (value != VpnConnectionState.CONNECTED) mutableTransportProtocol.value = VpnTransportProtocol.UNKNOWN
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update() else mainHandler.post(update)
    }

    fun observeDiagnostic(message: String) {
        val protocol = transportProtocolFromDiagnostic(message) ?: return
        val update = { mutableTransportProtocol.value = protocol }
        if (Looper.myLooper() == Looper.getMainLooper()) update() else mainHandler.post(update)
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
