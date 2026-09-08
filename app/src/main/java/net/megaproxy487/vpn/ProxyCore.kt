package net.megaproxy487.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import net.megaproxy487.model.ProxyConfig
import org.json.JSONObject
import org.json.JSONArray
import mobile.Mobile
import mobile.Protector
import mobile.Reporter
import net.megaproxy487.data.operationResult
import java.util.concurrent.atomic.AtomicBoolean

interface ProxyCore {
    fun resolveProxy(host: String, status: (String) -> Unit): String?
    fun start(tunFd: Int, mtu: Int, config: ProxyConfig, status: (String) -> Unit): Boolean
    fun test(config: ProxyConfig, status: (String) -> Unit): ConnectionTestResult?
    fun stop()
}

data class ConnectionTestResult(val exitIp: String, val countryCode: String?)

internal fun parseConnectionTestResult(raw: String): ConnectionTestResult {
    val result = JSONObject(raw)
    return ConnectionTestResult(
        exitIp = result.getString("exitIp"),
        countryCode = result.optString("countryCode").takeIf(String::isNotBlank),
    )
}

data class NativeConnectionStats(
    val downloadBytes: Long,
    val uploadBytes: Long,
    val proxyLatencyMillis: Double,
    val proxyLatencyAtMillis: Long,
    val connectionErrorRate: Double,
    val connectionSamples: Int,
    val totalOutcomes: Long,
)

object ConnectionStatsReader {
    fun snapshot(): NativeConnectionStats? = operationResult {
        val raw = Mobile.getStats()
        val json = JSONObject(raw)
        NativeConnectionStats(
            downloadBytes = json.getLong("downloadBytes"),
            uploadBytes = json.getLong("uploadBytes"),
            proxyLatencyMillis = json.getDouble("proxyLatencyMillis"),
            proxyLatencyAtMillis = json.getLong("proxyLatencyAtMillis"),
            connectionErrorRate = json.getDouble("connectionErrorRate"),
            connectionSamples = json.getInt("connectionSamples"),
            totalOutcomes = json.getLong("totalOutcomes"),
        )
    }.getOrNull()
}

/** JNI boundary for the Go/uTLS userspace TCP/IP stack. */
class NativeProxyCore(
    private val vpnService: VpnService,
    private val diagnostics: (String) -> Unit = DiagnosticLog::add,
    private val isCurrent: () -> Boolean = { true },
) : ProxyCore {
    private val callbacksEnabled = AtomicBoolean(true)
    private fun callbackFailure(error: Throwable) {
        android.util.Log.w("MegaProxy", "Native callback failed: ${error.javaClass.simpleName}")
    }
    private fun protector(): Protector = BridgeProtector(
        { callbacksEnabled.get() && isCurrent() }, vpnService::protect, ::callbackFailure,
    )
    private fun reporter(deliver: (String) -> Unit): Reporter = BridgeReporter(
        { callbacksEnabled.get() && isCurrent() }, deliver, ::callbackFailure,
    )

    private fun configJson(config: ProxyConfig) = JSONObject()
        .put("type", config.type.name)
        .put("host", config.host.trim())
        .put("dialHost", config.resolvedProxyIp)
        .put("port", config.port)
        .put("username", config.username)
        .put("password", config.password)
        .put("privateKey", config.privateKey)
        .put("sshProfile", config.sshProfile.name)
        .put("trustedHostKey", config.trustedHostKey)
        .put("acceptAnyHostKey", config.acceptAnyHostKey)
        .put("jumpHost", config.jumpHost.trim())
        .put("jumpDialHost", config.resolvedJumpIp)
        .put("jumpPort", config.jumpPort)
        .put("jumpUsername", config.jumpUsername)
        .put("jumpPassword", config.jumpPassword)
        .put("jumpPrivateKey", config.jumpPrivateKey)
        .put("jumpTrustedHostKey", config.jumpTrustedHostKey)
        .put("jumpAllowInvalidProxyCertificate", config.jumpAllowInvalidProxyCertificate)
        .put("jumpAcceptAnyHostKey", config.jumpAcceptAnyHostKey)
        .put("sameJumpAuthentication", config.sameJumpAuthentication)
        .put("sshAuthMode", config.sshAuthMode.name)
        .put("sshKeepaliveSeconds", config.sshKeepaliveSeconds)
        .put("sshMaxChannels", config.sshMaxChannels)
        .put("sshRotationMinutes", config.sshRotationMinutes)
        .put("sshRotationMb", config.sshRotationMb)
        .put("allowInvalidProxyCertificate", config.allowInvalidProxyCertificate)
        .put("profile", config.profile.name)
        .put("customJa3", config.customJa3.trim())
        .put("dohUrl", if (config.dnsProvider.url.isNotEmpty()) config.dnsProvider.url else config.customDohUrl.trim())
        .put("dohFallbackUrls", JSONArray(config.dnsProvider.fallbackUrls()))
        .put("allowIpv6", config.allowIpv6)
        .put("bypassLocalNetworks", config.bypassLocalNetworks)
        .toString()

    override fun resolveProxy(host: String, status: (String) -> Unit): String? = operationResult {
        Mobile.resolveProxy(host, protector(), reporter(diagnostics))
    }.onFailure {
        val message = it.cause?.message ?: it.message ?: "Unknown native error"
        diagnostics("event=bootstrap_dns result=failed detail=$message")
        status("Proxy DNS failed: $message")
    }.getOrNull()

    override fun start(tunFd: Int, mtu: Int, config: ProxyConfig, status: (String) -> Unit): Boolean {
        var nativeStarted = false
        return operationResult {
            val reporter = reporter { message ->
                diagnostics(message)
                VpnRuntimeState.observeDiagnostic(message)
                if ("SSH_HOST_KEY_" in message || "dpi_hint=possible" in message) status(message)
            }
            val json = configJson(config)
            // Java keeps its duplicate alive for the call; Go duplicates it on entry.
            // Even a linkage failure before Go is entered cannot leak a detached FD.
            ParcelFileDescriptor.fromFd(tunFd).use { borrowed ->
                Mobile.start(borrowed.fd.toLong(), mtu.toLong(), json, protector(), reporter)
                nativeStarted = true
            }
            status("TCP is protected by ${config.type.title}")
            true
        }.getOrElse {
            if (nativeStarted) stop()
            status("Native core error: ${it.message}")
            false
        }
    }

    override fun test(config: ProxyConfig, status: (String) -> Unit): ConnectionTestResult? = operationResult {
        val reporter = reporter { message ->
            diagnostics(message)
            if ("SSH_HOST_KEY_" in message) status(message)
        }
        val raw = Mobile.testConnection(configJson(config), protector(), reporter)
        parseConnectionTestResult(raw)
    }.onSuccess {
        status("Test passed: exit IP ${it.exitIp}")
    }.onFailure {
        val message = it.cause?.message ?: it.message ?: "Unknown native error"
        diagnostics("event=connection_test result=failed detail=$message")
        status("Test failed: $message")
    }.getOrNull()

    override fun stop() {
        callbacksEnabled.set(false)
        operationResult { Mobile.stop() }.onFailure(::callbackFailure)
    }
}
