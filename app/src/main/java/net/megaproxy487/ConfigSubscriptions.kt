package net.megaproxy487

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.megaproxy487.data.*
import net.megaproxy487.model.ProxyProfile
import net.megaproxy487.vpn.PersistentDiagnosticLog
import net.megaproxy487.vpn.ProxyVpnService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream

internal const val SUBSCRIPTION_JOB_ID = 48703
internal const val SUBSCRIPTION_CLIENT_ID = "android"

internal fun subscriptionDelay(state: ConfigSubscriptionState, now: Long): Long =
    if (state.lastAttempt == 0L) 0 else
        (state.lastAttempt.coerceAtMost(now) + state.settings.intervalMinutes * 60_000L - now).coerceAtLeast(0)

/** Full downloads, no conditional cache, redirects, retries, cookies or interactive authentication. */
internal fun downloadConfiguration(
    settings: ConfigSubscription, url: String,
    open: (URI) -> HttpURLConnection = { it.toURL().openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection },
    checkActive: () -> Unit = {},
): String {
    settings.validate()
    require(url in listOf(settings.url) + settings.fallbackUrls)
    val connection = open(URI(url))
    val deadline = System.nanoTime() + 45_000_000_000L
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("X-MegaProxy-Client", SUBSCRIPTION_CLIENT_ID)
        connection.setRequestProperty("X-MegaProxy-Version", BuildConfig.VERSION_NAME)
        connection.setRequestProperty("Accept-Encoding", "gzip")
        connection.setRequestProperty("Cookie", "")
        if (settings.username != null || settings.password != null) connection.setRequestProperty("Authorization",
            "Basic " + Base64.getEncoder().encodeToString("${settings.username.orEmpty()}:${settings.password.orEmpty()}".toByteArray(Charsets.UTF_8)))
        checkActive()
        require(connection.responseCode == 200)
        return connection.inputStream.use { raw ->
            val stream = when (connection.contentEncoding?.lowercase()) {
                null, "", "identity" -> raw
                "gzip" -> GZIPInputStream(raw)
                else -> throw java.io.IOException("Unsupported content encoding")
            }
            stream.readConfigText {
                checkActive()
                if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException()
            }
        }
    } finally { connection.disconnect() }
}

internal fun parseSubscriptionSnapshot(text: String, previous: List<ProxyProfile>): PortableConfiguration {
    // Remote definitions cannot redirect this subscription or acquire additional credential recipients.
    val parsed = parseProfileImport(text, null, "", acceptSubscription = false)
    if (parsed is ParsedProfileImport.Configuration) {
        val value = parsed.value
        val root = boundedJsonObject(text)
        if (root.optInt("version") == ConfigTransfer.SCHEMA_VERSION) validateSubscriptionConfiguration(root)
        val ids = value.profiles.map(ProxyProfile::id).toSet()
        for (key in listOf("activeProfileId", "alwaysOnProfileId")) {
            if (root.has(key) && !root.isNull(key)) require(root.get(key) is String && root.getString(key) in ids)
        }
        root.optJSONObject("failover")?.optJSONArray("profileIds")?.let { array ->
            require((0 until array.length()).all { array.get(it) is String && array.getString(it) in ids })
        }
        return value
    }
    val list = (parsed as ParsedProfileImport.ProxyList).value
    val used = mutableSetOf<String>()
    fun endpoint(profile: ProxyProfile) = listOf(profile.config.type.name,
        profile.config.host.lowercase(), profile.config.port.toString()).joinToString("\u0000")
    val provisional = list.proxies.map { source -> ProxyProfile(
        UUID.randomUUID().toString(), source.name, 0, source.countryCode, source.config) }
    // ponytail: unique matching scans at most 1,000 profiles; index names/endpoints if imports become slow.
    val profiles = provisional.map { source ->
        val names = provisional.count { it.name == source.name }
        val byName = previous.filter { source.name.isNotBlank() && names == 1 && it.name == source.name }
        val endpoints = provisional.count { endpoint(it) == endpoint(source) }
        val byEndpoint = previous.filter { endpoints == 1 && endpoint(it) == endpoint(source) }
        val match = byName.singleOrNull() ?: byEndpoint.singleOrNull()
        val reusable = match?.takeIf { used.add(it.id) }
        if (reusable == null) source.also { used.add(it.id) } else reusable.copy(
            name = source.name.ifBlank { reusable.name },
            countryCode = source.countryCode.ifBlank { reusable.countryCode },
            config = reusable.config.copy(type = source.config.type, host = source.config.host,
                port = source.config.port, username = source.config.username, password = source.config.password),
        )
    }
    return PortableConfiguration(profiles, null, null, skippedProfiles = list.skippedNonHttps,
        secretPresence = profiles.associate { it.id to ProfileSecretPresence(password = it.config.password.isNotEmpty()) })
}

/** Application scope keeps manual transfers alive across navigation and configuration changes. */
internal object ConfigSubscriptions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableRevision = MutableStateFlow(0)
    val revision = mutableRevision.asStateFlow()
    private val mutableError = MutableStateFlow(false)
    val error = mutableError.asStateFlow()

    fun configure(context: Context, settings: ConfigSubscription?) {
        val application = context.applicationContext
        scope.launch {
            mutableError.value = false
            try { withContext(ConfigIoDispatcher) { ConfigStore(application).saveSubscription(settings) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableError.value = true }
            finally { mutableRevision.value++ }
        }
    }

    fun refresh(context: Context) {
        val application = context.applicationContext
        if (mutableBusy.value) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutableError.value = false
            try { refreshNow(application, manual = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableError.value = true; mutableRevision.value++ }
        }
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val state = operationResult { ConfigStore(context).subscriptionState() }.getOrNull()
        if (state == null || !state.settings.enabled) {
            scheduler.cancel(SUBSCRIPTION_JOB_ID)
            return
        }
        val stamp = "${state.generation}:${state.lastAttempt}"
        if (scheduler.getPendingJob(SUBSCRIPTION_JOB_ID)?.extras?.getString("stamp") == stamp) return
        scheduler.schedule(JobInfo.Builder(SUBSCRIPTION_JOB_ID, ComponentName(context, ConfigSubscriptionService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(subscriptionDelay(state, System.currentTimeMillis()))
            .setExtras(PersistableBundle().apply { putString("stamp", stamp) })
            .setPersisted(true).build())
    }

    internal suspend fun refreshNow(
        context: Context, manual: Boolean,
        download: suspend (ConfigSubscription, String) -> String = { settings, url ->
            withContext(Dispatchers.IO) {
                val job = coroutineContext
                downloadConfiguration(settings, url, checkActive = { job.ensureActive() })
            }
        },
        connectionRunning: () -> Boolean = { ProxyVpnService.isRunning },
    ): Boolean = mutex.withLock {
        mutableBusy.value = true
        try {
            val store = ConfigStore(context)
            val state = withContext(ConfigIoDispatcher) { store.subscriptionState() } ?: return@withLock false
            if (!manual && (!state.settings.enabled || subscriptionDelay(state, System.currentTimeMillis()) > 0)) return@withLock false
            for ((index, url) in (listOf(state.settings.url) + state.settings.fallbackUrls).withIndex()) {
                try {
                    val text = download(state.settings, url)
                    var reconnectToken: String? = null
                    val applied = withContext(ConfigIoDispatcher) {
                        val current = store.subscriptionState()?.takeIf { it.generation == state.generation }
                            ?: return@withContext false
                        val owned = store.profiles().filter { it.id in current.ownedIds }
                        val parsed = parseSubscriptionSnapshot(text, owned)
                        val previousToken = store.pendingReconnectToken()
                        store.applySubscriptionSnapshot(state.generation, parsed, index,
                            System.currentTimeMillis(), connectionRunning()).also { applied ->
                            if (applied) reconnectToken = store.pendingReconnectToken()?.takeIf { it != previousToken }
                        }
                    }
                    if (applied) operationResult { PersistentDiagnosticLog.setLimitMb(store.diagnosticLogLimitMb()) }
                    if (applied && !manual) reconnectToken?.let { token ->
                        operationResult { SubscriptionNotifications.show(context, token, connectionRunning) }
                    }
                    return@withLock applied
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* No URLs, credentials, payloads or remote error messages in logs. */ }
            }
            withContext(ConfigIoDispatcher) { store.recordSubscriptionFailure(state.generation, System.currentTimeMillis()) }
            false
        } finally {
            mutableBusy.value = false
            mutableRevision.value++
            if (manual) schedule(context)
        }
    }
}

class ConfigSubscriptionService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var check: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        check = scope.launch {
            try { ConfigSubscriptions.refreshNow(this@ConfigSubscriptionService, manual = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The screen reports unreadable stored settings; never retry without credentials. */ }
            jobFinished(params, false)
            ConfigSubscriptions.schedule(this@ConfigSubscriptionService)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        check?.cancel()
        return true
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
