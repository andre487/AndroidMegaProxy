package net.megaproxy487

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigWrites
import net.megaproxy487.model.GlobalConnectionSettings
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.vpn.ProxyVpnService
import net.megaproxy487.vpn.VpnConnectionState
import net.megaproxy487.vpn.VpnRuntimeState
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.net.InetSocketAddress
import java.net.Socket

/** Real Application, Android Keystore, service and generated JNI; no platform shadows. */
abstract class DeviceTestBase {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context = instrumentation.targetContext
    protected val arguments = InstrumentationRegistry.getArguments()
    protected val device = UiDevice.getInstance(instrumentation)
    protected val store get() = ConfigStore(context)
    protected val compose = createAndroidComposeRule<MainActivity>()
    protected fun argument(name: String) = requireNotNull(arguments.getString(name)) { "Missing fixture argument: $name" }
    protected fun text(id: Int) = context.getString(id)
    protected fun node(id: Int) = compose.onNodeWithText(text(id))
    protected fun io(block: () -> Unit) = runBlocking { withContext(ConfigIoDispatcher) { block() } }
    protected fun saved() {
        io {} // Barrier behind every ordered write, including service commands.
        assertEquals(0, ConfigWrites.status.value.pending)
        assertFalse("Configuration write failed", ConfigWrites.status.value.failed)
    }
    protected fun await(message: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { message }
            SystemClock.sleep(50)
        }
    }
    protected fun vpnPresent(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }
    protected fun stopped() {
        saved()
        await("VPN did not stop") { !ProxyVpnService.isRunning && !vpnPresent() && VpnRuntimeState.connection.value == VpnConnectionState.DISCONNECTED }
        assertFalse(store.isConnectionDesired())
    }
    protected fun systemButton(resource: String) {
        val button = device.wait(Until.findObject(By.res(resource)), 10_000)
        assertNotNull("Missing system button $resource", button)
        button.click()
        device.waitForIdle()
    }
    protected fun connect() {
        node(R.string.connect).performScrollTo().performClick()
        if (VpnService.prepare(context) != null) systemButton("android:id/button1")
        await("Real VPN did not connect") { ProxyVpnService.isRunning && vpnPresent() && VpnRuntimeState.connection.value == VpnConnectionState.CONNECTED }
        saved()
    }
    protected fun roundTrip() {
        val payload = "MegaProxy device ${System.nanoTime()}"
        Socket().use { socket ->
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress(argument("originHost"), 8080), 15_000)
            val request = "POST /echo HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\nContent-Length: ${payload.length}\r\n\r\n$payload"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            val response = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readText()
            assertTrue("Origin did not return HTTP 200", response.startsWith("HTTP/1.0 200"))
            assertTrue(response.contains("X-MegaProxy-Origin: integration"))
            assertEquals(payload, response.substringAfter("\r\n\r\n"))
        }
    }
    protected fun directOriginUnavailable() {
        assertFalse("Origin is reachable without the VPN", runCatching {
            Socket().use { it.connect(InetSocketAddress(argument("originHost"), 8080), 1_000) }
        }.isSuccess)
    }

    private val platform = object : ExternalResource() {
        override fun before() {
            if (arguments.getString("phase") != "verify") io {
                assertTrue(context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE).edit().clear().commit())
                val profile = store.activeProfile()
                store.saveProfile(profile.copy(name = "Device fixture", config = ProxyConfig(
                    host = "10.0.2.2", port = argument("proxyPort").toInt(), username = "exit",
                    password = argument("proxyPassword"), allowInvalidProxyCertificate = true,
                )))
                store.saveGlobalConnectionSettings(GlobalConnectionSettings(bypassLocalNetworks = false))
            }
            assertTrue(context.getSharedPreferences("battery_optimization_reminder", Context.MODE_PRIVATE)
                .edit().putLong("last_request_at", System.currentTimeMillis()).commit())
        }
    }
    @After fun cleanup() {
        try {
            ProxyVpnService.stop(context)
            stopped()
        } finally {
            saved()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(platform).around(compose)
}
