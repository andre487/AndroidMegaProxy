package net.megaproxy487.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import net.megaproxy487.model.ProxyConfig
import org.json.JSONObject
import java.lang.reflect.Proxy

interface ProxyCore {
    fun resolveProxy(host: String, status: (String) -> Unit): String?
    fun start(tunFd: Int, config: ProxyConfig, status: (String) -> Unit): Boolean
    fun test(config: ProxyConfig, status: (String) -> Unit): String?
    fun stop()
}

data class NativeConnectionStats(
    val downloadBytes: Long,
    val uploadBytes: Long,
    val proxyLatencyMillis: Double,
    val proxyLatencyAtMillis: Long,
    val connectionErrorRate: Double,
    val connectionSamples: Int,
)

object ConnectionStatsReader {
    fun snapshot(): NativeConnectionStats? = runCatching {
        val raw = Class.forName("mobile.Mobile").getMethod("getStats").invoke(null) as String
        val json = JSONObject(raw)
        NativeConnectionStats(
            downloadBytes = json.getLong("downloadBytes"),
            uploadBytes = json.getLong("uploadBytes"),
            proxyLatencyMillis = json.getDouble("proxyLatencyMillis"),
            proxyLatencyAtMillis = json.getLong("proxyLatencyAtMillis"),
            connectionErrorRate = json.getDouble("connectionErrorRate"),
            connectionSamples = json.getInt("connectionSamples"),
        )
    }.getOrNull()
}

/** JNI boundary for the Go/uTLS userspace TCP/IP stack. */
class NativeProxyCore(
    private val vpnService: VpnService,
    private val diagnostics: (String) -> Unit = DiagnosticLog::add,
) : ProxyCore {
    private fun protectSocket(fd: Long): Boolean = vpnService.protect(fd.toInt())

    private fun configJson(config: ProxyConfig) = JSONObject()
        .put("host", config.host.trim())
        .put("dialHost", config.resolvedProxyIp)
        .put("port", config.port)
        .put("username", config.username)
        .put("password", config.password)
        .put("allowInvalidProxyCertificate", config.allowInvalidProxyCertificate)
        .put("profile", config.profile.name)
        .put("customJa3", config.customJa3.trim())
        .put("dohUrl", if (config.dnsProvider.url.isNotEmpty()) config.dnsProvider.url else config.customDohUrl.trim())
        .put("allowIpv6", config.allowIpv6)
        .put("bypassLocalNetworks", config.bypassLocalNetworks)
        .toString()

    private fun callback(type: Class<*>, methodName: String, callback: (Array<out Any?>?) -> Any?) =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            if (method.name == methodName) callback(args) else error("Unknown native callback ${method.name}")
        }

    override fun resolveProxy(host: String, status: (String) -> Unit): String? = runCatching {
        val mobile = Class.forName("mobile.Mobile")
        val protectorType = Class.forName("mobile.Protector")
        val reporterType = Class.forName("mobile.Reporter")
        val protector = callback(protectorType, "protect") { protectSocket(it!![0] as Long) }
        val reporter = callback(reporterType, "report") { diagnostics(it!![0] as String); null }
        mobile.getMethod("resolveProxy", String::class.java, protectorType, reporterType)
            .invoke(null, host, protector, reporter) as String
    }.onFailure {
        val message = it.cause?.message ?: it.message ?: "Unknown native error"
        diagnostics("Proxy bootstrap DNS failed for $host: $message")
        status("Proxy DNS failed: $message")
    }.getOrNull()

    override fun start(tunFd: Int, config: ProxyConfig, status: (String) -> Unit): Boolean {
        var detachedFd: Int? = null
        return runCatching {
            val mobile = Class.forName("mobile.Mobile")
            val protectorType = Class.forName("mobile.Protector")
            val reporterType = Class.forName("mobile.Reporter")
            val protector = callback(protectorType, "protect") { protectSocket(it!![0] as Long) }
            val reporter = callback(reporterType, "report") { diagnostics(it!![0] as String); null }
            detachedFd = ParcelFileDescriptor.fromFd(tunFd).detachFd()
            val startMethod = mobile.getMethod(
                "start", Long::class.javaPrimitiveType, String::class.java, protectorType, reporterType,
            )
            val goFd = detachedFd!!
            detachedFd = null // Start's contract takes ownership, including error paths.
            startMethod.invoke(null, goFd.toLong(), configJson(config), protector, reporter)
            status("TCP is protected by the HTTPS proxy")
            true
        }.getOrElse {
            detachedFd?.let { fd -> runCatching { ParcelFileDescriptor.adoptFd(fd).close() } }
            status(if (it is ClassNotFoundException) "Add app/libs/megaproxy.aar" else "Native core error: ${it.cause?.message ?: it.message}")
            false
        }
    }

    override fun test(config: ProxyConfig, status: (String) -> Unit): String? = runCatching {
        val mobile = Class.forName("mobile.Mobile")
        val protectorType = Class.forName("mobile.Protector")
        val reporterType = Class.forName("mobile.Reporter")
        val protector = callback(protectorType, "protect") { protectSocket(it!![0] as Long) }
        val reporter = callback(reporterType, "report") { diagnostics(it!![0] as String); null }
        mobile.getMethod("testConnection", String::class.java, protectorType, reporterType)
            .invoke(null, configJson(config), protector, reporter) as String
    }.onSuccess {
        status("Test passed: exit IP $it")
    }.onFailure {
        val message = it.cause?.message ?: it.message ?: "Unknown native error"
        diagnostics("Connection test failed: $message")
        status("Test failed: $message")
    }.getOrNull()

    override fun stop() {
        runCatching { Class.forName("mobile.Mobile").getMethod("stop").invoke(null) }
    }
}
