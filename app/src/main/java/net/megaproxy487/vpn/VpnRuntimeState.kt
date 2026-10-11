package net.megaproxy487.vpn

import java.util.concurrent.atomic.AtomicLong
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

enum class VpnConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
enum class VpnTransportProtocol { UNKNOWN, SOCKS5, HTTP_1_1, HTTP_2, HTTP_3, SSH_MULTIPLEXED }

fun transportProtocolFromDiagnostic(message: String): VpnTransportProtocol? = when {
    "event=connection" in message && "protocol=socks5" in message &&
        "stage=tunnel" in message && "result=established" in message -> VpnTransportProtocol.SOCKS5
    "event=connection" in message && "protocol=http3" in message &&
        "stage=tunnel" in message && "result=established" in message -> VpnTransportProtocol.HTTP_3
    "event=connection" in message && "protocol=http2" in message &&
        "stage=tunnel" in message && "result=established" in message -> VpnTransportProtocol.HTTP_2
    "event=connection" in message && "mode=proxy" in message &&
        "stage=tunnel" in message && "result=established" in message -> VpnTransportProtocol.HTTP_1_1
    "event=transport_capability" in message && "transport=ssh" in message &&
        "multiplexed=true" in message -> VpnTransportProtocol.SSH_MULTIPLEXED
    else -> null
}

internal fun isHttp3FallbackDiagnostic(message: String): Boolean =
    "event=transport_selection" in message && "selected=https" in message && "result=fallback" in message

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
    private val mutableHttp3Fallback = mutableStateOf(false)
    val http3Fallback: State<Boolean> = mutableHttp3Fallback

    private val mutableSession = mutableStateOf<ConnectionSession?>(null)
    val session: State<ConnectionSession?> = mutableSession

    private val connectionRevision = AtomicLong()
    private val systemRevision = AtomicLong()
    private val warningRevision = AtomicLong()
    private val diagnosticEpoch = AtomicLong()

    fun update(value: VpnConnectionState, isCurrent: () -> Boolean = { true }) {
        if (!isCurrent()) return
        val revision = connectionRevision.incrementAndGet()
        if (value != VpnConnectionState.CONNECTED) diagnosticEpoch.incrementAndGet()
        val wallTime = System.currentTimeMillis()
        val elapsedTime = SystemClock.elapsedRealtime()
        val update = update@{
            if (!isCurrent() || connectionRevision.get() != revision) return@update
            mutableSession.value = connectionSessionForState(
                mutableSession.value, value == VpnConnectionState.CONNECTED, wallTime, elapsedTime,
            )
            mutableConnection.value = value
            if (value != VpnConnectionState.CONNECTED) {
                mutableTransportProtocol.value = VpnTransportProtocol.UNKNOWN
                mutableHttp3Fallback.value = false
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update() else mainHandler.post(update)
    }

    fun observeDiagnostic(message: String, isCurrent: () -> Boolean = { true }) {
        val epoch = diagnosticEpoch.get()
        val protocol = transportProtocolFromDiagnostic(message)
        val fallback = isHttp3FallbackDiagnostic(message)
        if (protocol == null && !fallback) return
        val update = update@{
            if (!isCurrent() || diagnosticEpoch.get() != epoch) return@update
            if (protocol != null) mutableTransportProtocol.value = protocol
            if (fallback) mutableHttp3Fallback.value = true
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update() else mainHandler.post(update)
    }

    fun updateNetworkWarning(value: String?, isCurrent: () -> Boolean = { true }) {
        if (!isCurrent()) return
        val revision = warningRevision.incrementAndGet()
        val update = {
            if (isCurrent() && warningRevision.get() == revision) mutableNetworkWarning.value = value
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update() else mainHandler.post(update)
    }

    fun updateSystem(alwaysOn: Boolean, lockdown: Boolean, connectionProfileId: String, isCurrent: () -> Boolean = { true }) {
        if (!isCurrent()) return
        val revision = systemRevision.incrementAndGet()
        val update = update@{
            if (!isCurrent() || systemRevision.get() != revision) return@update
            mutableAlwaysOn.value = alwaysOn
            mutableLockdown.value = lockdown
            mutableConnectionProfileId.value = connectionProfileId
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update() else mainHandler.post(update)
    }
}
