package net.megaproxy487

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.model.GlobalConnectionSettings
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.model.ProxyType
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.PersistentDiagnosticLog
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Disposable device smoke test. No runner framework or production test hooks. */
class WaydroidSmokeInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = requireNotNull(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var resultCode = Activity.RESULT_OK
        try {
            val host = requireNotNull(arguments.getString("server"))
            val origin = requireNotNull(arguments.getString("origin"))
            val password = requireNotNull(arguments.getString("password"))
            val fingerprint = requireNotNull(arguments.getString("fingerprint"))
            // The firewall must make the private origin unreachable without the VPN.
            check(runCatching { Socket().use { it.connect(InetSocketAddress(origin, 8080), 3000) } }.isFailure) {
                "Private origin is directly reachable; the test would not prove proxy forwarding"
            }
            val store = ConfigStore(targetContext)
            store.saveGlobalConnectionSettings(GlobalConnectionSettings(
                routeAllApps = false,
                selectedPackages = setOf(targetContext.packageName),
                bypassLocalNetworks = false,
            ))
            store.saveProfile(store.activeProfile().copy(name = "Disposable Waydroid SSH", config = ProxyConfig(
                type = ProxyType.SSH, host = host, port = 2222,
                username = "proxy", password = password, trustedHostKey = fingerprint,
            )))
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            check(VpnService.prepare(targetContext) == null) { "VPN was not pre-authorized" }
            ProxyVpnService.start(targetContext)
            val deadline = SystemClock.elapsedRealtime() + 45_000
            while (!ProxyVpnService.isRunning && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250)
            check(ProxyVpnService.isRunning) { "Production VPN service did not connect" }
            val payload = UUID.randomUUID().toString()
            val response = Socket().use { socket ->
                socket.connect(InetSocketAddress(origin, 8080), 15_000)
                socket.soTimeout = 15_000
                socket.getOutputStream().write(
                    ("POST /echo HTTP/1.1\r\nHost: $origin\r\nContent-Length: ${payload.length}\r\nConnection: close\r\n\r\n$payload").toByteArray()
                )
                socket.getInputStream().bufferedReader().readText()
            }
            check(response.startsWith("HTTP/1.0 200") || response.startsWith("HTTP/1.1 200"))
            check(response.contains("X-MegaProxy-Origin: integration"))
            check(response.endsWith(payload)) { "Echo payload differs" }
            result.putString("smoke", "passed")
            result.putString("coverage", "real Keystore, VpnService, JNI/TUN, OpenSSH and private HTTP echo")

        } catch (error: Exception) {
            result.putString("smoke", "failed")
            result.putString("reason", error.javaClass.simpleName + ": " + error.message)
            result.putString("diagnostics", PersistentDiagnosticLog.readTail(16_384))
            resultCode = Activity.RESULT_CANCELED
        } finally {
            ProxyVpnService.stop(targetContext)
        }
        finish(resultCode, result)
    }
}
