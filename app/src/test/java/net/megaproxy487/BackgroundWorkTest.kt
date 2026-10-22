package net.megaproxy487

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.model.ProxyConfig
import net.megaproxy487.vpn.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.security.Security
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class BackgroundWorkTest {
    @Before fun prepare() {
        Security.removeProvider("AndroidKeyStore")
        UiTestKeyStore.keys.clear()
        Security.addProvider(UiTestKeyStoreProvider())
        net.megaproxy487.data.ConfigStore(RuntimeEnvironment.getApplication()).activeProfile()
    }
    @After fun cleanup() {
        runBlocking { withContext(ConfigIoDispatcher) {} }
        Security.removeProvider("AndroidKeyStore")
        UiTestKeyStore.keys.clear()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun slowNativeStopLeavesMainFreeAndKeepsLaterControlWorkOrdered() {
        val controller = Robolectric.buildService(ProxyVpnService::class.java)
        val service = controller.get()
        val control = shadowOf(service.monitorHandler.looper)
        control.pause()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stoppedOnMain = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>()
        val fake = object : ProxyCore {
            override fun resolveProxy(host: String, status: (String) -> Unit): String? = error("Unexpected DNS")
            override fun start(tunFd: Int, mtu: Int, config: ProxyConfig, status: (String) -> Unit) = error("Unexpected start")
            override fun test(config: ProxyConfig, status: (String) -> Unit): ConnectionTestResult? = error("Unexpected test")
            override fun stop() {
                stoppedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        ProxyVpnService::class.java.getDeclaredField("core").apply { isAccessible = true }.set(service, fake)
        // No actual VPN/JNI or device permissions are needed for this boundary.
        service.onStartCommand(Intent("net.megaproxy487.STOP"), 0, 1)
        val later = AtomicBoolean()
        service.monitorHandler.post { later.set(true) }
        val drain = thread {
            try { control.idle() } catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(stoppedOnMain.get())
            assertFalse(later.get())
            var uiResponded = false
            Handler(Looper.getMainLooper()).post { uiResponded = true }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(uiResponded)
        } finally {
            release.countDown()
            drain.join(5_000)
            controller.destroy()
            control.idle()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertFalse(drain.isAlive)
        assertNull(failure.get())
        assertTrue(later.get())
    }

    @Test fun subscriptionSchedulingDoesNotReadStorageOnMain() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readOnMain = AtomicBoolean(true)
        val wrapped = object : SharedPreferences by prefs {
            override fun getString(key: String?, defaultValue: String?): String? {
                if (key == "config_subscription_v1") {
                    readOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return prefs.getString(key, defaultValue)
            }
        }
        val application = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "proxy_config") wrapped else super.getSharedPreferences(name, mode)
        }
        ConfigSubscriptions.schedule(application)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(readOnMain.get())
            var uiResponded = false
            Handler(Looper.getMainLooper()).post { uiResponded = true }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(uiResponded)
        } finally {
            release.countDown()
            runBlocking { withContext(ConfigIoDispatcher) {} }
        }
    }

    @Test fun sessionChangedDoesNotReplaceAnAlreadyQueuedSuccessfulResult() {
        TestDiagnosticLog.begin()
        val worker = thread {
            TestDiagnosticLog.succeed("192.0.2.1", null)
            TestDiagnosticLog.failIfRunning("Session changed")
        }
        worker.join(5_000)
        shadowOf(Looper.getMainLooper()).idle()
        try {
            assertEquals(TestState.SUCCEEDED, TestDiagnosticLog.state.value)
        } finally { TestDiagnosticLog.reset() }
    }
}
