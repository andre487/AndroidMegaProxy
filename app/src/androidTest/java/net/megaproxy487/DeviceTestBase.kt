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
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.io.File
import java.net.InetAddress
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
        // Runtime state can arrive before Compose commits the matching accessibility tree.
        device.waitForIdle()
        if (!device.wait(Until.hasObject(By.text(text(id))), 10_000)) {
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
        // A successful round trip to the private origin proves Android has
        // installed this UID's VPN route before the one-shot UDP DNS probe.
        dnsPolicyRoundTrip()
    }
    // Local AAAA suppression proves both DNS transports reach the native TUN
    // interceptor. Network DoH/fallback is tested by the native TLS fixtures.
    private fun dnsPolicyRoundTrip() {
        val query = byteArrayOf(0x48, 0x73, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7) +
            "example".toByteArray(Charsets.US_ASCII) + byteArrayOf(4) +
            "test".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 28, 0, 1)
        fun verify(response: ByteArray) {
            assertTrue(response.size >= 12)
            assertEquals(0x48, response[0].toInt())
            assertEquals(0x73, response[1].toInt())
            assertTrue(response[2].toInt() and 0x80 != 0)
            assertEquals(0, response[6].toInt())
            assertEquals(0, response[7].toInt())
        }
        java.net.DatagramSocket().use { socket ->
            socket.soTimeout = 15_000
            socket.send(java.net.DatagramPacket(query, query.size, InetAddress.getByName("10.77.0.2"), 53))
            val reply = java.net.DatagramPacket(ByteArray(512), 512)
            socket.receive(reply)
            verify(reply.data.copyOf(reply.length))
        }
        Socket().use { socket ->
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress("10.77.0.2", 53), 15_000)
            val output = java.io.DataOutputStream(socket.getOutputStream())
            val input = java.io.DataInputStream(socket.getInputStream())
            repeat(2) { output.writeShort(query.size); output.write(query) }
            repeat(2) {
                val response = ByteArray(input.readUnsignedShort())
                input.readFully(response)
                verify(response)
            }
        }
    }

    protected fun directOriginUnavailable() {
        assertFalse("Origin is reachable without the VPN", runCatching {
            Socket().use { it.connect(InetSocketAddress(argument("originHost"), 8080), 1_000) }
        }.isSuccess)
    }

    private fun prepare() {
        io {
            PersistentDiagnosticLog.initialize(context, PersistentDiagnosticLog.DEFAULT_LIMIT_MB)
            PersistentDiagnosticLog.clear()
            flushDiagnosticLog()
            PersistentDiagnosticLog.write("event=device_test stage=start")
        }
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
    private fun captureFailure(error: Throwable) {
        println("Device failure: ${error.javaClass.simpleName}")
        val directory = requireNotNull(context.getExternalFilesDir(null))
        device.takeScreenshot(File(directory, "device-failure.png"))
        device.dumpWindowHierarchy(File(directory, "device-failure.xml"))
    }

    private fun flushDiagnosticLog() {
        // Test-only barrier after VPN shutdown; a busy/discarded barrier fails with a timeout.
        val field = PersistentDiagnosticLog::class.java.getDeclaredField("executor").apply { isAccessible = true }
        val executor = field.get(null) as ThreadPoolExecutor
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    @get:Rule val rules: org.junit.rules.TestRule = DeviceTestEvidenceRule(
        prepare = ::prepare,
        cleanup = { if (::scenario.isInitialized) cleanup() },
        close = { if (::scenario.isInitialized) scenario.close() },
        evidence = ::captureFailure,
        diagnostics = {
            io {
                PersistentDiagnosticLog.write("event=device_test stage=finish")
                flushDiagnosticLog()
            }
            println("MegaProxy diagnostic tail:\n${PersistentDiagnosticLog.readTail(64 * 1024)}")
        },
    )
}
