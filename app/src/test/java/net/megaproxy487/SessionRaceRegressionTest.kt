package net.megaproxy487

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import net.megaproxy487.data.*
import net.megaproxy487.vpn.ProxyVpnService
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.Security
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SessionRaceRegressionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = ConfigStore(context)
    @Before fun prepare() {
        Security.removeProvider("AndroidKeyStore")
        UiTestKeyStore.keys.clear()
        Security.addProvider(UiTestKeyStoreProvider())
        store.activeProfile()
    }
    @After fun cleanup() {
        Security.removeProvider("AndroidKeyStore")
        UiTestKeyStore.keys.clear()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
    private fun awaitBlockedOrFinished(worker: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (worker.isAlive && worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue("Worker neither finished nor reached the storage lock",
            !worker.isAlive || worker.state == Thread.State.BLOCKED)
    }

    @Test fun selectionAndDeletionAreSerializedAcrossStores() {
        val victim = store.addProfile()
        val prefs = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val intercept = AtomicBoolean(true)
        val wrapped = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        if (key == "active_profile_id" && value == victim.id && intercept.compareAndSet(true, false)) {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        editor.putString(key, value)
                        return this
                    }
                }
            }
        }
        val wrapper = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "proxy_config") wrapped else super.getSharedPreferences(name, mode)
        }
        val selection = ConfigStore(wrapper)
        val failure = AtomicReference<Throwable?>()
        val worker = thread { try { selection.setActiveProfile(victim.id) } catch (t: Throwable) { failure.set(t) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val deletion = thread { try { store.deleteProfile(victim.id) } catch (t: Throwable) { failure.set(t) } }
        awaitBlockedOrFinished(deletion)
        release.countDown()
        worker.join(5_000)
        deletion.join(5_000)
        assertFalse(worker.isAlive)
        assertFalse(deletion.isAlive)
        assertNull(failure.get())
        assertTrue(store.profiles().none { it.id == victim.id })
        assertNotEquals(victim.id, store.activeProfileId())
    }
    @Test fun importReviewPreservesLatestUnselectedOptions() {
        val initial = store.activeProfile()
        val before = initial.copy(config = initial.config.copy(allowInvalidProxyCertificate = true))
        store.saveProfile(before)
        val imported = before.copy(name = "Imported", config = before.config.copy(port = 9443))
        store.saveProfile(before.copy(config = before.config.copy(port = 8443, allowInvalidProxyCertificate = false)))
        store.importConfiguration(PortableConfiguration(listOf(imported), null, null)) { current, incoming ->
            applySelectedProfileOptions(current, incoming, emptySet())
        }
        assertEquals(8443, store.profile(before.id)!!.config.port)
        assertFalse(store.profile(before.id)!!.config.allowInvalidProxyCertificate)
    }
    @Test fun staleStopCannotOverrideNewerManualStart() {
        val controller = Robolectric.buildService(ProxyVpnService::class.java)
        org.robolectric.Shadows.shadowOf(controller.get().monitorHandler.looper).pause()
        controller.create()
        val service = controller.get()
        val busy = ProxyVpnService::class.java.getDeclaredField("startRunning").apply { isAccessible = true }.get(null) as AtomicBoolean
        busy.set(true) // Keep JNI out of this command-order test.
        try {
            ProxyVpnService.stop(context)
            ProxyVpnService.start(context)
            drainCommands()
            val application = org.robolectric.Shadows.shadowOf(context)
            val stop = application.nextStartedService!!
            val start = application.nextStartedService!!
            assertEquals("net.megaproxy487.STOP", stop.action)
            assertEquals("net.megaproxy487.START_MANUAL", start.action)
            val generation = ProxyVpnService::class.java.getDeclaredField("startGeneration").apply { isAccessible = true }
                .get(service) as java.util.concurrent.atomic.AtomicLong
            val before = generation.get()
            service.onStartCommand(stop, 0, 1)
            org.robolectric.Shadows.shadowOf(service.monitorHandler.looper).idle()
            assertEquals("Stale Stop must not tear down the current session", before, generation.get())
            service.onStartCommand(start, 0, 2)
            org.robolectric.Shadows.shadowOf(service.monitorHandler.looper).idle()
            assertTrue(store.isConnectionDesired())
        } finally { busy.set(false); controller.destroy() }
    }
    private fun drainCommands() = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withContext(ConfigIoDispatcher) {}
    }

    @Test fun queuedStartCannotOverrideLaterStop() {
        val controller = Robolectric.buildService(ProxyVpnService::class.java)
        org.robolectric.Shadows.shadowOf(controller.get().monitorHandler.looper).pause()
        controller.create()
        try {
            ProxyVpnService.start(context)
            ProxyVpnService.stop(context)
            drainCommands()
            val application = org.robolectric.Shadows.shadowOf(context)
            val start = application.nextStartedService!!
            val stop = application.nextStartedService!!
            controller.get().onStartCommand(start, 0, 1)
            controller.get().onStartCommand(stop, 0, 2)
            org.robolectric.Shadows.shadowOf(controller.get().monitorHandler.looper).idle()
            assertFalse(store.isConnectionDesired())
            assertFalse(ProxyVpnService.isRunning)
        } finally { controller.destroy() }
    }

    @Test fun lateDeleteCompletionCannotReconnectAfterStop() {
        val victim = store.activeProfile()
        store.addProfile()
        store.setConnectionProfile(victim.id)
        store.setConnectionDesired(true)
        val reconnect = store.isConnectionDesired() && store.connectionProfile().id == victim.id
        store.deleteProfile(victim.id)
        ProxyVpnService.stop(context)
        drainCommands()
        assertFalse(store.isConnectionDesired())
        val application = org.robolectric.Shadows.shadowOf(context)
        assertEquals("net.megaproxy487.STOP", application.nextStartedService?.action)
        // Resume the UI continuation captured before the user's later Stop.
        if (reconnect) ProxyVpnService.reconnect(context)
        drainCommands()
        assertFalse(store.isConnectionDesired())
        assertNull(application.nextStartedService)
    }

    @Test fun hostKeyResumeRequiresTheCommandThatWasCurrentBeforeSaving() {
        store.setConnectionDesired(false) // A host-key failure pauses the connection.
        val token = store.connectionCommandToken()!!
        ProxyVpnService.reconnect(context, token)
        drainCommands()
        val application = org.robolectric.Shadows.shadowOf(context)
        assertEquals("net.megaproxy487.RECONNECT", application.nextStartedService?.action)
        assertTrue(store.isConnectionDesired())

        val pendingSaveToken = store.connectionCommandToken()!!
        ProxyVpnService.stop(context)
        drainCommands()
        assertEquals("net.megaproxy487.STOP", application.nextStartedService?.action)
        ProxyVpnService.reconnect(context, pendingSaveToken)
        drainCommands()
        assertFalse(store.isConnectionDesired())
        assertNull(application.nextStartedService)
    }

    @Test fun destroyedServiceCannotPublishNetworkWarning() {
        val controller = Robolectric.buildService(ProxyVpnService::class.java)
        org.robolectric.Shadows.shadowOf(controller.get().monitorHandler.looper).pause()
        controller.create()
        val service = controller.get()
        val callback = ProxyVpnService::class.java.getDeclaredField("networkCallback").apply { isAccessible = true }
            .get(service) as android.net.ConnectivityManager.NetworkCallback
        val network = org.robolectric.util.ReflectionHelpers.callConstructor(android.net.Network::class.java,
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 123))
        val capabilities = android.net.NetworkCapabilities()
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<android.net.NetworkCapabilities>(capabilities, "addCapability",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL))
        controller.destroy()
        net.megaproxy487.vpn.VpnRuntimeState.updateNetworkWarning(null)
        // The framework callback had already been dispatched when unregister ran.
        callback.onCapabilitiesChanged(network, capabilities)
        org.robolectric.Shadows.shadowOf(service.monitorHandler.looper).idle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(net.megaproxy487.vpn.VpnRuntimeState.networkWarning.value)
        net.megaproxy487.vpn.VpnRuntimeState.updateNetworkWarning(null)
    }

    @Test fun failedTransactionCannotRollbackConcurrentStop() {
        store.setConnectionDesired(true)
        val prefs = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        val failNext = AtomicBoolean(true)
        val stopWorker = AtomicReference<Thread>()
        val wrapped = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                    val result = method.invoke(editor, *(args ?: emptyArray()))
                    if (method.name == "commit" && failNext.compareAndSet(true, false)) {
                        val stop = thread { store.setConnectionDesired(false) }
                        stopWorker.set(stop)
                        awaitBlockedOrFinished(stop)
                        false
                    } else if (result is SharedPreferences.Editor) proxy else result
                } as SharedPreferences.Editor
            }
        }
        val wrapper = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = wrapped
        }
        assertThrows(UiException::class.java) {
            ConfigStore(wrapper).saveSubscription(ConfigSubscription("https://feed.example/config"))
        }
        stopWorker.get().join(5_000)
        assertFalse(stopWorker.get().isAlive)
        assertFalse(store.isConnectionDesired())
    }

}
