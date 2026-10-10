package net.megaproxy487

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
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
import net.megaproxy487.vpn.PersistentDiagnosticLog
import net.megaproxy487.vpn.VpnConnectionState
import net.megaproxy487.vpn.VpnRuntimeState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Real Application, Android Keystore, service and generated JNI; no platform shadows. */
abstract class DeviceTestBase {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val context = instrumentation.targetContext
    protected val arguments = InstrumentationRegistry.getArguments()
    protected val device = UiDevice.getInstance(instrumentation)
    protected val store get() = ConfigStore(context)
    protected lateinit var scenario: ActivityScenario<MainActivity>
    protected fun argument(name: String) = requireNotNull(arguments.getString(name)) { "Missing fixture argument: $name" }
    protected fun text(id: Int) = context.getString(id)
    protected fun appNode(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2 {
        device.waitForIdle()
        val result = device.wait(Until.findObject(selector), 10_000)
        assertNotNull("Missing app control $selector", result)
        return result
    }
    protected fun click(id: Int) {
        if (!device.hasObject(By.text(text(id)))) {
            device.findObject(UiSelector().scrollable(true)).let {
                if (it.exists()) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(text(id)))
            }
        }
        appNode(By.text(text(id))).click()
    }
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
        device.waitForIdle()
        // Invoke the button's semantic action: coordinate injection can be ignored
        // by PermissionController even when its accessibility node is visible.
        val node = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(resource)
            ?.firstOrNull { it.isClickable && it.isEnabled }) { "System button is not actionable: $resource" }
        assertTrue("System button rejected click: $resource", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertTrue("System dialog did not close: $resource", device.wait(Until.gone(By.res(resource)), 10_000))
        device.waitForIdle()
    }
    protected fun connect() {
        click(R.string.connect)
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
            listOf("png", "xml").forEach { File(context.getExternalFilesDir(null), "device-failure.$it").delete() }
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
            scenario = ActivityScenario.launch(MainActivity::class.java)
            device.waitForIdle()
        }
    }
    private fun cleanup() {
        repeat(3) {
            if (device.currentPackageName != context.packageName) {
                device.pressBack()
                device.waitForIdle()
            }
        }
        try {
            ProxyVpnService.stop(context)
            stopped()
        } finally {
            saved()
        }
    }
    private val evidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            // The disposable fixture uses synthetic credentials. This is the app's
            // sanitized, bounded log, including recovery events absent from logcat.
            println("MegaProxy diagnostic tail:\n${PersistentDiagnosticLog.readTail(32 * 1024)}")
            val directory = context.getExternalFilesDir(null)!!
            device.takeScreenshot(File(directory, "device-failure.png"))
            device.dumpWindowHierarchy(File(directory, "device-failure.xml"))
        }
    }
    private val activity = object : ExternalResource() {
        override fun after() { scenario.close() }
    }
    private val finish = object : ExternalResource() {
        override fun after() { cleanup() }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(platform).around(activity).around(finish).around(evidence)
}
