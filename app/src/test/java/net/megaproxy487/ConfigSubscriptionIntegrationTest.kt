package net.megaproxy487

import android.app.Application
import android.app.NotificationManager
import org.robolectric.Shadows.shadowOf
import android.app.job.JobScheduler
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import net.megaproxy487.data.*
import net.megaproxy487.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ConfigSubscriptionIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = ConfigStore(context)
    private val settings = ConfigSubscription("https://feed.example/config?token=private-url-token",
        listOf("https://backup.example/config"), "subscriber", "subscription-secret")

    @Before fun installKeys() {
        Security.removeProvider("AndroidKeyStore")
        UiTestKeyStore.keys.clear()
        Security.addProvider(UiTestKeyStoreProvider())
    }
    @After fun removeKeys() {
        context.getSystemService(JobScheduler::class.java).cancel(SUBSCRIPTION_JOB_ID)
        Security.removeProvider("AndroidKeyStore")
        UiTestKeyStore.keys.clear()
    }
    private fun profile(id: String) = ProxyProfile(id, id, 0, "",
        ProxyConfig(type = ProxyType.SOCKS5, host = "$id.example", port = 1080))
    private fun document(vararg profiles: ProxyProfile, subscription: ConfigSubscription? = null) = JSONObject()
        .put("schema", ConfigTransfer.SCHEMA_ID).put("version", 8)
        .put("profiles", JSONArray(profiles.map { ConfigTransfer.encodeProfile(it, true, false) }))
        .apply { subscription?.let { put("subscription", it.toJson()) } }
    private fun bootstrap(vararg profiles: ProxyProfile) {
        store.importConfiguration(ConfigTransfer.importJson(document(*profiles, subscription = settings).toString()))
    }
    private fun apply(value: PortableConfiguration, running: Boolean = false) = store.applySubscriptionSnapshot(
        store.subscriptionState()!!.generation, value, 0, 1000, running)

    @Test fun settingsTokensAndCredentialsAreEncryptedAndExportFollowsPasswordChoice() {
        store.saveProfile(store.activeProfile().let { it.copy(config = profile("initial").config) })
        store.saveSubscription(settings)
        assertEquals(settings, ConfigStore(context).subscriptionState()!!.settings)
        val raw = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE).all.toString()
        for (secret in listOf("private-url-token", "subscription-secret", "subscriber")) assertFalse(raw.contains(secret))
        val withSecrets = ConfigTransfer.exportJson(store, true)
        val without = ConfigTransfer.exportJson(store, false)
        ConfigSchemas.assertValid(withSecrets)
        ConfigSchemas.assertValid(without)
        assertEquals("subscription-secret", JSONObject(withSecrets).getJSONObject("subscription").getString("password"))
        assertFalse(JSONObject(without).getJSONObject("subscription").has("password"))
        assertFalse(JSONObject(withSecrets).getJSONObject("subscription").has("ownedIds"))
        assertFalse(JSONObject(withSecrets).has("lastSuccess"))
    }

    @Test fun missingDefinitionPreservesExplicitNullRemovesAndSameSourceRetainsPassword() {
        bootstrap(profile("one"))
        store.importConfiguration(ConfigTransfer.importJson(document(profile("two")).toString()))
        assertEquals(settings, store.subscriptionState()!!.settings)
        val omitted = document(profile("one"), subscription = settings.copy(password = null))
        store.importConfiguration(ConfigTransfer.importJson(omitted.toString()))
        assertEquals(settings.password, store.subscriptionState()!!.settings.password)
        omitted.put("subscription", JSONObject.NULL)
        store.importConfiguration(ConfigTransfer.importJson(omitted.toString()))
        assertNull(store.subscriptionState())
        assertNotNull(store.profile("one"))
        assertNotNull(store.profile("two"))
    }

    @Test fun snapshotsReplaceOnlyOwnedProfilesPreserveSelectionAndRepairRemovedReferences() {
        val local = store.activeProfile()
        bootstrap(profile("one"), profile("two"))
        store.setActiveProfile("one")
        store.setAlwaysOnProfile("one")
        store.setConnectionProfile("one")
        store.setConnectionDesired(false)
        store.setFailoverState(true, "old notice")
        apply(PortableConfiguration(listOf(profile("two"), profile("three")), "three", "two"))
        assertNull(store.profile("one"))
        assertNotNull(store.profile(local.id))
        assertEquals("three", store.activeProfileId())
        assertEquals("three", store.alwaysOnProfileId())
        assertEquals("three", store.connectionProfileId())
        assertFalse(store.isFailoverActive())
        assertNull(store.failoverNotice())
        assertFalse(store.isConnectionDesired())
        store.setActiveProfile(local.id)
        apply(PortableConfiguration(listOf(profile("two")), "two", "two"))
        assertEquals(local.id, store.activeProfileId())
        assertNull(store.profile("three"))
        assertEquals(setOf("two"), store.subscriptionState()!!.ownedIds)
    }

    @Test fun localIdCollisionsAndInvalidSnapshotsLeaveAllWorkingStateUntouched() {
        val local = store.activeProfile()
        bootstrap(profile("one"))
        val before = store.profiles()
        val state = store.subscriptionState()
        assertTrue(runCatching { apply(PortableConfiguration(listOf(local.copy(name = "hijacked")), null, null)) }.isFailure)
        assertTrue(runCatching { apply(PortableConfiguration(emptyList(), null, null)) }.isFailure)
        assertEquals(before, store.profiles())
        assertEquals(state, store.subscriptionState())
    }

    @Test fun onlyEffectiveChangesOfAnActiveConnectionRequestReconnect() {
        bootstrap(profile("one"), profile("two"))
        store.setActiveProfile("one")
        store.setConnectionProfile("one")
        apply(PortableConfiguration(listOf(profile("one"), profile("two").copy(name = "metadata")), null, null), true)
        assertFalse(store.hasPendingReconnect())
        apply(PortableConfiguration(listOf(profile("one").copy(config = profile("one").config.copy(port = 1081))), null, null), true)
        assertTrue(store.hasPendingReconnect())
        assertFalse(store.isConnectionDesired())
    }

    @Test fun omittedProfileSecretsSurviveAndExplicitEmptyClears() {
        val original = profile("one").copy(config = profile("one").config.copy(username = "user", password = "proxy-secret"))
        bootstrap(original)
        apply(PortableConfiguration(listOf(original.copy(config = original.config.copy(password = ""))), null, null))
        assertEquals("proxy-secret", store.profile("one")!!.config.password)
        apply(PortableConfiguration(listOf(original.copy(config = original.config.copy(password = ""))), null, null,
            secretPresence = mapOf("one" to ProfileSecretPresence(password = true))))
        assertEquals("", store.profile("one")!!.config.password)
    }

    @Test fun failoverTriesInOrderRestartsAtPrimaryAndManualRefreshWorksWhilePaused() = runBlocking {
        bootstrap(profile("one"))
        store.saveSubscription(settings.copy(enabled = false))
        val calls = mutableListOf<String>()
        val result = ConfigSubscriptions.refreshNow(context, manual = true, download = { _, url ->
            calls += url
            if (url == settings.url) "<html>login</html>" else document(profile("two")).toString()
        }, connectionRunning = { false })
        assertTrue(result)
        assertEquals(listOf(settings.url) + settings.fallbackUrls, calls)
        assertEquals(1, store.subscriptionState()!!.sourceIndex)
        assertFalse(store.subscriptionState()!!.settings.enabled)
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(SUBSCRIPTION_JOB_ID))
        calls.clear()
        ConfigSubscriptions.refreshNow(context, true, { _, url -> calls += url; document(profile("two")).toString() }, { false })
        assertEquals(listOf(settings.url), calls)
        calls.clear()
        assertFalse(ConfigSubscriptions.refreshNow(context, false, { _, url -> calls += url; error("must not download") }, { false }))
        assertTrue(calls.isEmpty())
    }

    @Test fun allSourceFailuresKeepSnapshotAndRecordSafeStatus() = runBlocking {
        bootstrap(profile("one"))
        val before = store.profiles()
        assertFalse(ConfigSubscriptions.refreshNow(context, true, { _, _ -> throw java.io.IOException("secret remote body") }, { false }))
        assertEquals(before, store.profiles())
        val state = store.subscriptionState()!!
        assertTrue(state.failed)
        assertEquals(0, state.lastSuccess)
        assertFalse(state.toJson().toString().contains("secret remote body"))
    }

    @Test fun editOrRemovalDuringDownloadDiscardsObsoleteSnapshot() = runBlocking {
        bootstrap(profile("one"))
        val before = store.profiles()
        assertFalse(ConfigSubscriptions.refreshNow(context, true, { _, _ ->
            store.saveSubscription(settings.copy(enabled = false))
            document(profile("two")).toString()
        }, { false }))
        assertEquals(before, store.profiles())
        assertEquals(0, store.subscriptionState()!!.lastSuccess)
        assertFalse(ConfigSubscriptions.refreshNow(context, true, { _, _ ->
            store.saveSubscription(null)
            document(profile("two")).toString()
        }, { false }))
        assertEquals(before, store.profiles())
        assertNull(store.subscriptionState())
    }

    @Test fun lostKeystoreKeyNeverDownloadsAndExplicitRemovalRecovers() = runBlocking {
        bootstrap(profile("one"))
        UiTestKeyStore.keys.clear()
        val calls = mutableListOf<String>()
        assertTrue(runCatching { ConfigSubscriptions.refreshNow(context, true,
            { _, url -> calls += url; "" }, { false }) }.isFailure)
        assertTrue(calls.isEmpty())
        store.saveSubscription(null)
        assertNull(store.subscriptionState())
    }

    @Test fun cancellationDoesNotCommitOrAdvanceSuccess() = runBlocking {
        bootstrap(profile("one"))
        val before = store.profiles()
        assertTrue(runCatching { ConfigSubscriptions.refreshNow(context, true,
            { _, _ -> throw CancellationException() }, { false }) }.exceptionOrNull() is CancellationException)
        assertEquals(before, store.profiles())
        assertEquals(0, store.subscriptionState()!!.lastSuccess)
        assertFalse(ConfigSubscriptions.busy.value)
    }
    @Test @Config(sdk = [26]) fun oneMinuteSchedulingPersistsAcrossStartupAndPauseCancelsIt() {
        store.saveSubscription(settings.copy(intervalMinutes = 1))
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val first = scheduler.getPendingJob(SUBSCRIPTION_JOB_ID)!!
        assertTrue(first.isPersisted)
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, first.networkType)
        assertEquals(0, first.minLatencyMillis)
        ConfigSubscriptions.schedule(context)
        assertEquals(first.extras.getString("stamp"), scheduler.getPendingJob(SUBSCRIPTION_JOB_ID)!!.extras.getString("stamp"))
        store.recordSubscriptionFailure(store.subscriptionState()!!.generation, System.currentTimeMillis())
        ConfigSubscriptions.schedule(context)
        assertTrue(scheduler.getPendingJob(SUBSCRIPTION_JOB_ID)!!.minLatencyMillis in 1..60_000)
        store.saveSubscription(settings.copy(enabled = false))
        assertNull(scheduler.getPendingJob(SUBSCRIPTION_JOB_ID))
    }

    @Test fun failedDiskCommitRestoresProfilesPreferencesOwnershipAndReconnectFlag() {
        bootstrap(profile("one"))
        store.globalConnectionSettings()
        val preferences = context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        val before = preferences.all
        var failNext = true
        val failingPrefs = object : android.content.SharedPreferences by preferences {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = preferences.edit()
                return java.lang.reflect.Proxy.newProxyInstance(
                    javaClass.classLoader, arrayOf(android.content.SharedPreferences.Editor::class.java)
                ) { proxy, method, args ->
                    val result = method.invoke(editor, *(args ?: emptyArray()))
                    if (method.name == "commit" && failNext) { failNext = false; false }
                    else if (result is android.content.SharedPreferences.Editor) proxy else result
                } as android.content.SharedPreferences.Editor
            }
        }
        val wrapped = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = failingPrefs
        }
        val failingStore = ConfigStore(wrapped)
        val state = failingStore.subscriptionState()!!
        assertTrue(runCatching {
            failingStore.applySubscriptionSnapshot(state.generation,
                PortableConfiguration(listOf(profile("two")), null, null,
                    globalConnectionSettings = GlobalConnectionSettings(routeAllApps = false)), 0, 1000, true)
        }.isFailure)
        assertFalse(failNext)
        assertEquals(before, preferences.all)
        assertNotNull(store.profile("one"))
        assertNull(store.profile("two"))
        assertEquals(state, store.subscriptionState())
        assertFalse(store.hasPendingReconnect())
    }


    @Test fun invalidLegacyPortsTryBackupAndNeverReplaceWorkingProfilesOnFailure() = runBlocking {
        bootstrap(profile("one"))
        val generation = store.subscriptionState()!!.generation
        val before = store.profiles()
        for (port in listOf(0, 65536, 70000)) {
            assertFalse(ConfigSubscriptions.refreshNow(context, true, { _, _ -> "socks5://bad.example:$port" }, { false }))
            assertEquals(before, store.profiles())
            assertEquals(generation, store.subscriptionState()!!.generation)
        }
        val calls = mutableListOf<String>()
        assertTrue(ConfigSubscriptions.refreshNow(context, true, { _, url ->
            calls += url
            if (url == settings.url) "socks5://bad.example:70000" else "socks5://good.example:1080"
        }, { false }))
        assertEquals(listOf(settings.url) + settings.fallbackUrls, calls)
        assertEquals(1, store.subscriptionState()!!.sourceIndex)
        assertEquals("good.example", store.profiles().single { it.id in store.subscriptionState()!!.ownedIds }.config.host)
    }

    @Test fun explicitDisableAuthClearsPasswordButImportOmissionStillRetainsIt() {
        for (username in listOf(null, "", "subscriber")) {
            store.saveSubscription(settings.copy(username = username))
            val draft = SubscriptionDraft().apply { load(store.subscriptionState()!!.settings); authenticated = false }
            store.saveSubscription(draft.settings())
            assertNull(store.subscriptionState()!!.settings.username)
            assertNull(store.subscriptionState()!!.settings.password)
        }
        val passwordOnly = settings.copy(username = null)
        store.saveSubscription(passwordOnly)
        store.importConfiguration(ConfigTransfer.importJson(document(profile("one"),
            subscription = passwordOnly.copy(password = null)).toString()))
        assertEquals(settings.password, store.subscriptionState()!!.settings.password)
    }

    @Test fun backgroundActiveChangesNotifyButManualMetadataAndStoppedUpdatesDoNot() = runBlocking {
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifications = shadowOf(manager)
        for ((manual, running, metadata) in listOf(Triple(false, true, false), Triple(true, true, false),
                Triple(false, true, true), Triple(false, false, false))) {
            manager.cancel(SUBSCRIPTION_NOTIFICATION_ID)
            context.getSharedPreferences("proxy_config", Context.MODE_PRIVATE).edit().clear().commit()
            bootstrap(profile("one"))
            store.setConnectionProfile("one")
            store.setConnectionDesired(running)
            store.clearPendingReconnect(store.pendingReconnectToken())
            val next = profile("one").let { if (metadata) it.copy(name = "renamed") else it.copy(config = it.config.copy(port = 1081)) }
            assertTrue(ConfigSubscriptions.refreshNow(context, manual, { _, _ -> document(next).toString() }, { running }))
            val notification = notifications.getNotification(SUBSCRIPTION_NOTIFICATION_ID)
            if (!manual && running && !metadata) {
                assertNotNull(notification)
                val text = notification.extras.toString()
                for (secret in listOf("feed.example", "subscription-secret", "subscriber", "one.example")) assertFalse(text.contains(secret))
                assertEquals(text(R.string.reconnect), notification.actions.single().title.toString())
                assertTrue(notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
                // An unchanged feed must not replace the pending token or create another alert.
                val token = store.pendingReconnectToken()
                assertTrue(ConfigSubscriptions.refreshNow(context, true, { _, _ -> document(next).toString() }, { running }))
                assertEquals(token, store.pendingReconnectToken())
            } else assertNull(notification)
        }
    }

    private fun text(id: Int) = context.uiText(id)

    @Test fun deletedProfilesCannotBeRestoredByStaleEditorWrites() {
        bootstrap(profile("one"), profile("two"))
        val draft = store.profile("one")!!
        val edit = ConfigEdits<ProxyProfile>().profile(draft, draft.copy(name = "rename"))
        assertTrue(store.deleteProfile("one"))
        edit.write { store.editProfile(draft.id, null, it) }
        assertNull(store.profile(draft.id))
    }

    @Test fun deniedNotificationsKeepPendingChangesAndStoppedActionsDoNotStartVpn() = runBlocking {
        bootstrap(profile("one"))
        store.setConnectionProfile("one")
        store.setConnectionDesired(true)
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        val next = profile("one").copy(config = profile("one").config.copy(port = 1081))
        assertTrue(ConfigSubscriptions.refreshNow(context, false, { _, _ -> document(next).toString() }, { true }))
        assertTrue(store.hasPendingReconnect())
        assertNull(shadowOf(manager).getNotification(SUBSCRIPTION_NOTIFICATION_ID))
        store.setConnectionDesired(false)
        SubscriptionReconnectReceiver().onReceive(context, android.content.Intent()
            .putExtra(EXTRA_SUBSCRIPTION_RECONNECT, store.pendingReconnectToken()))
        kotlinx.coroutines.withContext(ConfigIoDispatcher) { /* Drain the application command queue. */ }
        assertNull(shadowOf(context).nextStartedService)
        assertFalse(store.isConnectionDesired())
    }
}
