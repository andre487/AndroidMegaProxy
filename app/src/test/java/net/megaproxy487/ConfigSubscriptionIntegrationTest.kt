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
    private fun drainScheduling() = runBlocking {
        kotlinx.coroutines.withContext(ConfigIoDispatcher) {}
    }
    @After fun removeKeys() {
        drainScheduling()
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

    @Test fun jsonRefreshPreservesLocalDnsConsentOnlyForUnchangedResolver() {
        val original = profile("one").let { it.copy(config = it.config.copy(
            dnsProvider = DnsProvider.CUSTOM, customDohUrl = "https://dns.example/dns-query")) }
        bootstrap(original)
        store.saveProfile(original.copy(config = original.config.copy(
            allowInsecureDns = true, allowInvalidDohCertificate = true)))
        val rotated = original.copy(config = original.config.copy(password = "rotated"))
        assertTrue(apply(parseSubscriptionSnapshot(document(rotated).toString(), store.profiles())))
        val retained = ConfigStore(context).profile("one")!!.config
        assertTrue(retained.allowInsecureDns)
        assertTrue(retained.allowInvalidDohCertificate)
        assertEquals("rotated", retained.password)
        val changed = rotated.copy(config = rotated.config.copy(customDohUrl = "https://other.example/dns-query"))
        assertTrue(apply(parseSubscriptionSnapshot(document(changed).toString(), store.profiles())))
        val reset = ConfigStore(context).profile("one")!!.config
        assertFalse(reset.allowInsecureDns)
        assertFalse(reset.allowInvalidDohCertificate)
    }

    @Test fun plainDnsConsentSurvivesJsonImportForUnchangedResolver() {
        val original = profile("one").let { it.copy(config = it.config.copy(
            dnsProvider = DnsProvider.CUSTOM, customDohUrl = "tcp://1.1.1.1", allowInsecureDns = true)) }
        store.saveProfile(original, createIfMissing = true)
        store.importConfiguration(ConfigTransfer.importJson(document(original).toString()))
        assertTrue(ConfigStore(context).profile("one")!!.config.allowInsecureDns)
    }

    @Test fun omittedAndPartialGlobalsPreserveLocalSettingsOnImportAndRefresh() {
        bootstrap(profile("one"))
        store.setConnectionProfile("one")
        val local = GlobalConnectionSettings(
            tlsProfile = TlsProfile.FIREFOX_ANDROID, customJa3 = "local-ja3",
            sshProfile = SshProfile.OPENSSH_TERMUX, sshAuthMode = SshAuthMode.KEY_ONLY,
            sshKeepaliveSeconds = 90, sshMaxChannels = 16, sshRotationMinutes = 10, sshRotationMb = 128,
            failoverMode = FailoverMode.SELECTED, failoverProfileIds = listOf("one"),
            routeAllApps = false, selectedPackages = setOf("org.example.app"), bypassLocalNetworks = false,
        )
        store.saveGlobalConnectionSettings(local)
        store.setDiagnosticLogLimitMb(20)
        val json = document(profile("one"))
        val minimal = parseSubscriptionSnapshot(json.toString(), store.profiles())
        assertNull(minimal.globalConnectionSettings)
        assertTrue(apply(minimal, running = true))
        assertEquals(local, ConfigStore(context).globalConnectionSettings())
        assertEquals(20, store.diagnosticLogLimitMb())
        assertFalse(store.hasPendingReconnect())
        store.importConfiguration(ConfigTransfer.importJson(json.toString()))
        assertEquals(local, store.globalConnectionSettings())
        assertEquals(20, store.diagnosticLogLimitMb())

        json.put("tls", JSONObject().put("fingerprint", "CHROME_ANDROID"))
        json.put("routing", JSONObject().put("bypassLocalNetworks", true))
        val partial = parseSubscriptionSnapshot(json.toString(), store.profiles())
        // Resolve against storage at commit time, including edits made after parsing.
        val newer = local.copy(selectedPackages = setOf("org.example.new"))
        store.saveGlobalConnectionSettings(newer)
        assertTrue(apply(partial, running = true))
        val expected = newer.copy(tlsProfile = TlsProfile.CHROME_ANDROID, bypassLocalNetworks = true)
        assertEquals(expected, ConfigStore(context).globalConnectionSettings())
        assertEquals(20, store.diagnosticLogLimitMb())
        assertTrue(store.hasPendingReconnect())
        store.saveGlobalConnectionSettings(newer)
        store.importConfiguration(ConfigTransfer.importJson(json.toString()))
        assertEquals(expected, store.globalConnectionSettings())

        json.put("routing", JSONObject().put("routeAllApps", true).put("selectedPackages", JSONArray()))
        json.put("diagnosticLogLimitMb", 8)
        assertTrue(apply(parseSubscriptionSnapshot(json.toString(), store.profiles())))
        assertTrue(store.globalConnectionSettings().routeAllApps)
        assertTrue(store.globalConnectionSettings().selectedPackages.isEmpty())
        assertEquals(8, store.diagnosticLogLimitMb())
    }

    @Test fun updateDuringStartupRequiresReconnectAndNotificationAfterCommit() {
        bootstrap(profile("one"))
        store.setConnectionProfile("one")
        store.setConnectionDesired(true)
        val started = store.effectiveConnectionProfile()
        val tokenAtStart = store.pendingReconnectToken()
        val updated = profile("one").let { it.copy(config = it.config.copy(password = "rotated")) }
        apply(PortableConfiguration(listOf(updated), "one", "one", secretPresence = mapOf("one" to ProfileSecretPresence(password = true))), running = false)
        assertFalse(store.hasPendingReconnect())
        val token = store.reconcileStartedConfiguration(started.id, started.config, tokenAtStart)
        assertNotNull(token)
        shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        SubscriptionNotifications.show(context, token!!) { true }
        assertNotNull(shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(SUBSCRIPTION_NOTIFICATION_ID))
        val fresh = store.effectiveConnectionProfile()
        assertNull(store.reconcileStartedConfiguration(fresh.id, fresh.config, token))
    }

    @Test fun startupReconciliationPreservesNewerReconnectToken() {
        bootstrap(profile("one"))
        store.setConnectionProfile("one")
        store.markPendingReconnect()
        val oldToken = store.pendingReconnectToken()
        val started = store.effectiveConnectionProfile()
        store.markPendingReconnect()
        val newerToken = store.pendingReconnectToken()
        assertEquals(newerToken, store.reconcileStartedConfiguration(started.id, started.config, oldToken))
        assertNull(store.reconcileStartedConfiguration(started.id, started.config, newerToken))
    }

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
        drainScheduling()
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
        drainScheduling()
            document(profile("two"), subscription = settings.copy(url = "https://obsolete.example/config")).toString()
        }, { false }))
        assertEquals(before, store.profiles())
        assertEquals(0, store.subscriptionState()!!.lastSuccess)
        assertFalse(ConfigSubscriptions.refreshNow(context, true, { _, _ ->
            store.saveSubscription(null)
            document(profile("two"), subscription = settings.copy(url = "https://obsolete.example/config")).toString()
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
        drainScheduling()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val first = scheduler.getPendingJob(SUBSCRIPTION_JOB_ID)!!
        assertTrue(first.isPersisted)
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, first.networkType)
        assertEquals(0, first.minLatencyMillis)
        ConfigSubscriptions.schedule(context)
        drainScheduling()
        assertEquals(first.extras.getString("stamp"), scheduler.getPendingJob(SUBSCRIPTION_JOB_ID)!!.extras.getString("stamp"))
        store.recordSubscriptionFailure(store.subscriptionState()!!.generation, System.currentTimeMillis())
        ConfigSubscriptions.schedule(context)
        drainScheduling()
        assertTrue(scheduler.getPendingJob(SUBSCRIPTION_JOB_ID)!!.minLatencyMillis in 1..60_000)
        store.saveSubscription(settings.copy(enabled = false))
        drainScheduling()
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
                    globalConnectionSettings = GlobalConnectionSettings(routeAllApps = false),
                    subscription = settings.copy(url = "https://replacement.example/config")), 0, 1000, true)
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

    @Test fun refreshReplacesSourcesButRetainsLocalAuthIntervalAndPause() {
        bootstrap(profile("one"))
        val local = settings.copy(intervalMinutes = 123, enabled = false)
        store.saveSubscription(local)
        val remote = ConfigSubscription("https://next.example/config", listOf("https://next-backup.example/config"),
            "remote-user", "remote-secret", 1, true)
        assertTrue(apply(parseSubscriptionSnapshot(document(profile("one"), subscription = remote).toString(), store.profiles())))
        val next = ConfigStore(context).subscriptionState()!!
        assertEquals(local.copy(url = remote.url, fallbackUrls = remote.fallbackUrls), next.settings)
        assertEquals(setOf("one"), next.ownedIds)
        assertFalse(store.hasPendingReconnect())
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(SUBSCRIPTION_JOB_ID))
    }

    @Test fun missingOrNullRemoteDefinitionPreservesSourcesAndOmittedBackupsClearThem() {
        bootstrap(profile("one"))
        for (nullDefinition in listOf(false, true)) {
            val json = document(profile("one"))
            if (nullDefinition) json.put("subscription", JSONObject.NULL)
            assertTrue(apply(parseSubscriptionSnapshot(json.toString(), store.profiles())))
            assertEquals(settings, store.subscriptionState()!!.settings)
        }
        val json = document(profile("one")).put("subscription", JSONObject().put("url", "https://next.example/config"))
        assertTrue(apply(parseSubscriptionSnapshot(json.toString(), store.profiles())))
        assertEquals(settings.copy(url = "https://next.example/config", fallbackUrls = emptyList()), store.subscriptionState()!!.settings)
    }

    @Test fun invalidSourceDefinitionTriesBackupAndNextRefreshUsesNewSources() = runBlocking {
        bootstrap(profile("one"))
        val before = store.profiles()
        val replacement = ConfigSubscription("https://next.example/config", listOf("https://next-backup.example/config"))
        val calls = mutableListOf<String>()
        assertTrue(ConfigSubscriptions.refreshNow(context, true, { actual, url ->
            assertEquals(settings, actual)
            assertEquals(before, store.profiles())
            calls += url
            if (url == settings.url) document(profile("invalid")).put("subscription",
                JSONObject().put("url", "http://invalid.example/config")).toString()
            else document(profile("two"), subscription = replacement).toString()
        }, { false }))
        assertNull(store.profile("one"))
        assertNull(store.profile("invalid"))
        assertNotNull(store.profile("two"))
        val expected = settings.copy(url = replacement.url, fallbackUrls = replacement.fallbackUrls)
        assertEquals(expected, store.subscriptionState()!!.settings)
        assertTrue(ConfigSubscriptions.refreshNow(context, true, { actual, url ->
            assertEquals(expected, actual)
            calls += url
            document(profile("two")).toString()
        }, { false }))
        assertEquals(listOf(settings.url, settings.fallbackUrls.single(), replacement.url), calls)
    }

    @Test fun malformedSourceDefinitionsNeverCommitProfilesOrSources() = runBlocking {
        bootstrap(profile("one"))
        val before = store.profiles()
        for (definition in listOf<Any>(true, JSONObject(),
            JSONObject().put("url", "http://invalid.example"),
            JSONObject().put("url", "https://next.example").put("fallbackUrls", JSONArray().put("https://next.example/")),
            JSONObject().put("url", "https://next.example").put("username", 7),
            JSONObject().put("url", "https://next.example").put("enabled", "yes"))) {
            assertFalse(ConfigSubscriptions.refreshNow(context, true, { _, _ ->
                document(profile("invalid")).put("subscription", definition).toString()
            }, { false }))
            assertEquals(before, store.profiles())
            assertEquals(settings, store.subscriptionState()!!.settings)
            assertEquals(setOf("one"), store.subscriptionState()!!.ownedIds)
        }
    }

    @Test fun initialUrlRefreshUsesDownloadedSourcesAndLocallyEnteredPreferences() = runBlocking {
        val localProfile = store.activeProfile()
        val local = settings.copy(intervalMinutes = 37, enabled = false)
        store.saveSubscription(local)
        val remote = settings.copy(url = "https://new.example/config", fallbackUrls = emptyList(),
            username = "remote", password = "remote-secret", intervalMinutes = 1, enabled = true)
        assertTrue(ConfigSubscriptions.refreshNow(context, true, { actual, url ->
            assertEquals(local, actual)
            assertEquals(local.url, url)
            document(profile("one"), subscription = remote).toString()
        }, { false }))
        assertEquals(local.copy(url = remote.url, fallbackUrls = emptyList()), store.subscriptionState()!!.settings)
        assertEquals(setOf("one"), store.subscriptionState()!!.ownedIds)
        assertNotNull(store.profile(localProfile.id))
        assertFalse(store.isConnectionDesired())
    }

    @Test fun manualImportAndRefreshDoNotReuseAnOmittedPasswordForAChangedEndpoint() {
        val original = profile("one").let { it.copy(config = it.config.copy(username = "user", password = "saved-secret")) }
        val changed = document(original).apply {
            getJSONArray("profiles").getJSONObject(0).getJSONObject("proxy").apply {
                put("host", "changed.example")
                remove("password")
            }
        }
        bootstrap(original)
        store.importConfiguration(ConfigTransfer.importJson(changed.toString()))
        assertEquals("", store.profile("one")!!.config.password)
        store.saveProfile(original)
        assertTrue(apply(parseSubscriptionSnapshot(changed.toString(), store.profiles())))
        assertEquals("", store.profile("one")!!.config.password)
        assertEquals("changed.example", store.profile("one")!!.config.host)
    }
}
