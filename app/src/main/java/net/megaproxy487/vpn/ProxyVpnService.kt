package net.megaproxy487.vpn

import net.megaproxy487.R
import net.megaproxy487.titleRes
import net.megaproxy487.uiText
import net.megaproxy487.localizedName

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import net.megaproxy487.MainActivity
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.model.FailoverMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ProxyVpnService : VpnService() {
    @Volatile private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var core: ProxyCore? = null
    @Volatile private var activeConfig: net.megaproxy487.model.ProxyConfig? = null
    @Volatile private var tunnelTestOnly = false
    @Volatile private var hostKeyPrompt: PendingIntent? = null
    @Volatile private var failoverNotice: String? = null
    @Volatile private var connectionBlockedForAction = false
    @Volatile private var reconnectAfterStart = false
    @Volatile private var healthWarningActive = false
    @Volatile private var consecutiveStartFailures = 0
    @Volatile private var nextStartAttemptAt = 0L
    @Volatile private var retryStatus: String? = null
    private var lastHealthBytes = 0L
    private var lastHealthOutcomes = 0L
    private val tunnelStateLock = Any()
    private val startGeneration = AtomicLong(0)
    private val probableFailureCounts = ConcurrentHashMap<String, Int>()
    private val probableFailureTimes = ConcurrentHashMap<String, Long>()
    private val attemptedFailoverProfiles = ConcurrentHashMap.newKeySet<String>()
    private val freshDnsRecoveryProfiles = ConcurrentHashMap.newKeySet<String>()
    private val underlyingCapabilities = ConcurrentHashMap<Network, NetworkCapabilities>()
    @Volatile private var underlyingNetwork: Network? = null
    @Volatile private var networkCallbackRegistered = false
    private val monitorHandler = Handler(Looper.getMainLooper())
    private val reconnectForNetworkChange = Runnable {
        if (ConfigStore(this).isConnectionDesired() && tunnel != null && !testRunning.get()) {
            DiagnosticLog.add("event=network_handover action=reconnect")
            startService(Intent(this, ProxyVpnService::class.java).setAction(ACTION_RECONNECT)
                .putExtra(EXTRA_RECONNECT_REASON, "underlying_network_changed"))
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            reconsiderUnderlyingNetworks(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            underlyingCapabilities[network] = capabilities
            reconsiderUnderlyingNetworks(network)
        }

        override fun onLost(network: Network) {
            underlyingCapabilities.remove(network)
            reconsiderUnderlyingNetworks()
        }
    }
    private val monitor = object : Runnable {
        override fun run() {
            val desired = ConfigStore(this@ProxyVpnService).isConnectionDesired()
            if (desired) {
                updatePassiveHealthState()
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(failoverNotice ?: retryStatus ?: VpnRuntimeState.networkWarning.value ?: if (isRunning) this@ProxyVpnService.uiText(R.string.status_connected) else this@ProxyVpnService.uiText(R.string.status_reconnecting)),
                )
                val retryDue = SystemClock.elapsedRealtime() >= nextStartAttemptAt
                if (retryDue && tunnel == null && hostKeyPrompt == null && !connectionBlockedForAction && !testRunning.get() && startRunning.compareAndSet(false, true)) {
                    val generation = startGeneration.get()
                    VpnRuntimeState.update(VpnConnectionState.CONNECTING)
                    thread(name = "megaproxy-vpn-recovery") {
                        try {
                            startTunnel(testOnly = false, generation = generation)
                        } catch (error: Exception) {
                            if (!isStartCurrent(generation)) return@thread
                            handleStartFailure(false, "VPN recovery failed", error.message.orEmpty(), ConfigStore(this@ProxyVpnService).connectionProfile().id)
                        } finally {
                            finishStartAttempt()
                        }
                    }
                }
            }
            monitorHandler.postDelayed(this, MONITOR_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        failoverNotice = ConfigStore(this).failoverNotice()
        failoverNotice?.let(VpnRuntimeState::updateNetworkWarning)
        createChannel()
        registerUnderlyingNetworkCallback()
        monitorHandler.post(monitor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val systemAlwaysOnStart = intent?.action == SERVICE_INTERFACE
        // Android starts the selected service with SERVICE_INTERFACE on older releases;
        // the explicit isAlwaysOn property itself was only added in API 29.
        val platformAlwaysOn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAlwaysOn
        // ACTION_REFRESH_STATUS must be allowed to clear a stale true value after the
        // user disables Always-on in Android Settings.
        isAlwaysOnMode = platformAlwaysOn || systemAlwaysOnStart
        isLockdownMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isLockdownEnabled else false
        val store = ConfigStore(this)
        // Android may call the sticky service again with a null intent. Do not replace a
        // failover profile on every callback; only a fresh system Always-on start selects
        // the configured base profile.
        if (isAlwaysOnMode && intent?.action != ACTION_START_MANUAL) {
            store.setConnectionDesired(true)
            if (intent?.action == SERVICE_INTERFACE && !store.isFailoverActive()) {
                store.setConnectionProfile(store.alwaysOnProfileId())
                store.setFailoverState(false, null)
                failoverNotice = null
                VpnRuntimeState.updateNetworkWarning(null)
            }
        }
        if (intent?.action == ACTION_START_MANUAL) {
            probableFailureCounts.clear(); probableFailureTimes.clear(); attemptedFailoverProfiles.clear(); failoverNotice = null
            freshDnsRecoveryProfiles.clear()
            connectionBlockedForAction = false
            resetRetryState()
            store.setFailoverState(false, null)
            VpnRuntimeState.updateNetworkWarning(null)
        }
        VpnRuntimeState.updateSystem(isAlwaysOnMode, isLockdownMode, store.connectionProfileId())
        if (intent?.action == ACTION_REFRESH_STATUS) {
            return if (tunnel != null) START_STICKY else START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISMISS_HOST_KEY) {
            hostKeyPrompt = null
            if (tunnel == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RECONNECT) {
            hostKeyPrompt = null; failoverNotice = null
            connectionBlockedForAction = false
            resetRetryState()
            store.setFailoverState(false, null)
            probableFailureCounts.clear(); probableFailureTimes.clear(); attemptedFailoverProfiles.clear()
            VpnRuntimeState.updateNetworkWarning(null)
            DiagnosticLog.add("event=vpn_reconnect reason=${intent.getStringExtra(EXTRA_RECONNECT_REASON) ?: "settings_changed"}")
            startForeground(NOTIFICATION_ID, notification(this@ProxyVpnService.uiText(R.string.status_reconnecting)))
            stopTunnel(removeForeground = false)
            val generation = startGeneration.get()
            ConfigStore(this).setConnectionDesired(true)
            if (startRunning.compareAndSet(false, true)) {
                VpnRuntimeState.update(VpnConnectionState.CONNECTING)
                thread(name = "megaproxy-vpn-reconnect") {
                    try {
                        startTunnel(testOnly = false, generation = generation)
                    } catch (error: Exception) {
                        if (!isStartCurrent(generation)) return@thread
                        handleStartFailure(false, "VPN reconnect failed", error.message.orEmpty(), ConfigStore(this@ProxyVpnService).connectionProfile().id)
                    } finally {
                        finishStartAttempt()
                    }
                }
            } else reconnectAfterStart = true
            return START_STICKY
        }
        if (intent?.action == ACTION_TEST) {
            if (startRunning.get()) {
                TestDiagnosticLog.fail("Wait for the current VPN connection attempt to finish")
                return START_NOT_STICKY
            }
            if (!testRunning.compareAndSet(false, true)) {
                TestDiagnosticLog.add("Connection test is already running")
                return START_NOT_STICKY
            }
            hostKeyPrompt = null
            SshHostKeyPromptState.clear()
            startForeground(NOTIFICATION_ID, notification(this@ProxyVpnService.uiText(R.string.status_testing_connection)))
            TestDiagnosticLog.begin()
            thread(name = "megaproxy-connection-test") {
                try {
                    testConnection()
                } catch (error: Exception) {
                    TestDiagnosticLog.fail("Connection test stopped unexpectedly: ${error.message ?: error.javaClass.simpleName}")
                    if (tunnelTestOnly) stopTunnel()
                } finally {
                    testRunning.set(false)
                }
            }
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(this@ProxyVpnService.uiText(R.string.status_connecting_progress)))
        if (tunnel == null && startRunning.compareAndSet(false, true)) {
            val generation = startGeneration.get()
            VpnRuntimeState.update(VpnConnectionState.CONNECTING)
            thread(name = "megaproxy-vpn-start") {
                try {
                    startTunnel(testOnly = false, generation = generation)
                } catch (error: Exception) {
                    if (!isStartCurrent(generation)) return@thread
                    handleStartFailure(false, "VPN start failed", error.message.orEmpty(), ConfigStore(this@ProxyVpnService).connectionProfile().id)
                } finally {
                    finishStartAttempt()
                }
            }
        }
        return START_STICKY
    }

    private fun finishStartAttempt() {
        startRunning.set(false)
        if (reconnectAfterStart) {
            reconnectAfterStart = false
            monitorHandler.post {
                startService(Intent(this, ProxyVpnService::class.java).setAction(ACTION_RECONNECT)
                    .putExtra(EXTRA_RECONNECT_REASON, "profile_changed_during_connect"))
            }
        }
    }

    private fun testConnection() {
        val storedConfig = ConfigStore(this).globalConnectionSettings().applyTo(ConfigStore(this).activeProfile().config)
        storedConfig.connectionValidationError()?.let { uiText(it) }?.let {
            TestDiagnosticLog.fail("Connection test cannot start: $it")
            if (tunnel == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return
        }
        val temporaryVpn = tunnel == null
        if (temporaryVpn && !startTunnel(testOnly = true, suppliedConfig = storedConfig, generation = startGeneration.get())) {
            TestDiagnosticLog.fail("Connection test failed: temporary VPN could not be started")
            if (hostKeyPrompt == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        val config = synchronized(tunnelStateLock) { activeConfig } ?: run {
            TestDiagnosticLog.fail("Connection test failed: active VPN configuration is unavailable")
            return
        }
        val result = NativeProxyCore(this, TestDiagnosticLog::add).test(config) { message ->
            configureHostKeyPrompt(message, ConfigStore(this).activeProfileId(), true)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(uiText(if (hostKeyPrompt != null) R.string.ssh_key_approval else if (isRunning) R.string.status_connected else R.string.status_connecting_progress)))
        }
        if (result != null) TestDiagnosticLog.succeed(result.exitIp, result.countryCode) else TestDiagnosticLog.fail()
        if (temporaryVpn) {
            stopTunnel()
            stopSelf()
        }
    }

    private fun startTunnel(
        testOnly: Boolean,
        suppliedConfig: net.megaproxy487.model.ProxyConfig? = null,
        generation: Long = startGeneration.get(),
    ): Boolean {
        val configStore = ConfigStore(this)
        val pendingReconnectToken = if (testOnly) null else configStore.pendingReconnectToken()
        val storedProfile = configStore.connectionProfile()
        val storedConfig = suppliedConfig ?: configStore.globalConnectionSettings().applyTo(storedProfile.config)
        val promptProfileId = if (testOnly) configStore.activeProfileId() else storedProfile.id
        val diagnostics = if (testOnly) TestDiagnosticLog::add else DiagnosticLog::add
        var failureDetail = ""
        diagnostics(
            if (testOnly) "event=vpn_start mode=test"
            else if (storedConfig.routeAllApps) "event=vpn_start mode=global"
            else "event=vpn_start mode=split selected_app_count=${storedConfig.selectedPackages.size}"
        )
        val validationError = if (testOnly) storedConfig.connectionValidationError()?.let { uiText(it) } else storedConfig.validationError()?.let { uiText(it) }
        validationError?.let {
            if (testOnly) TestDiagnosticLog.fail(it) else {
                val notice = if (isAlwaysOnMode)
                    this@ProxyVpnService.uiText(R.string.vpn_config_always_on)
                else this@ProxyVpnService.uiText(R.string.vpn_config_attention)
                VpnRuntimeState.updateNetworkWarning(notice)
                if (isAlwaysOnMode) {
                    connectionBlockedForAction = true
                    startForeground(NOTIFICATION_ID, notification(notice))
                    VpnRuntimeState.update(VpnConnectionState.CONNECTING)
                } else {
                    ConfigStore(this).setConnectionDesired(false)
                    VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return false
        }
        val builder = Builder()
            .setSession("MegaProxy")
            // Leave headroom for cellular, hotspot and nested-tunnel encapsulation.
            // 1400 avoids common PMTU black holes while remaining efficient for TCP.
            .setMtu(VPN_MTU)
            .addAddress("10.77.0.1", 30)
            .addAddress("fd77::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("10.77.0.2")
            .setBlocking(true)
        if (testOnly) {
            builder.addAllowedApplication(packageName)
        } else if (!storedConfig.routeAllApps) {
            var allowedCount = 0
            storedConfig.selectedPackages.forEach { selectedPackage ->
                runCatching { builder.addAllowedApplication(selectedPackage) }
                    .onSuccess { allowedCount++ }
                    .onFailure { diagnostics("event=routing package=unavailable action=skipped") }
            }
            if (allowedCount == 0) {
                // An empty allow-list means "all apps" to VpnService.Builder. Route only
                // our own process so an empty/missing split list cannot become global VPN.
                builder.addAllowedApplication(packageName)
                diagnostics("event=routing mode=split selected_app_count=0 fallback=self_only")
            }
        }
        val establishedTunnel = builder.establish() ?: run {
            handleStartFailure(testOnly, "VPN interface could not be established")
            return false
        }
        diagnostics("TUN established with IPv4, IPv6 and intercepted DNS")
        val proxyCore = NativeProxyCore(this, diagnostics)
        val addressCache = BootstrapAddressCache(this)
        fun resolveHost(host: String, target: String): String? {
            if (!testOnly) {
                addressCache.get(host)?.let {
                    diagnostics("event=bootstrap_dns result=cache_hit target=$target age_limit_days=7")
                    return it
                }
            }
            return proxyCore.resolveProxy(host) { message ->
                failureDetail = message
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(uiText(if (hostKeyPrompt != null) R.string.ssh_key_approval else if (isRunning) R.string.status_connected else R.string.status_connecting_progress)))
            }?.also { addressCache.put(host, it) }
        }
        val proxyIp = if (storedConfig.type == net.megaproxy487.model.ProxyType.HTTPS_JUMP) "" else resolveHost(storedConfig.host, "proxy") ?: run {
            establishedTunnel.close()
            if (!isStartCurrent(generation)) return false
            handleStartFailure(testOnly, "Proxy bootstrap DNS failed", failureDetail, promptProfileId)
            return false
        }
        val jumpIp = if (storedConfig.type.hasJump) {
            resolveHost(storedConfig.jumpHost, "jump") ?: run {
                establishedTunnel.close()
                if (!isStartCurrent(generation)) return false
                handleStartFailure(testOnly, "Jump host bootstrap DNS failed", failureDetail, promptProfileId)
                return false
            }
        } else ""
        if (!isStartCurrent(generation)) {
            establishedTunnel.close()
            return false
        }
        val config = storedConfig.copy(resolvedProxyIp = proxyIp, resolvedJumpIp = jumpIp)
        val started = proxyCore.start(establishedTunnel.fd, config) { message ->
                failureDetail = message
                configureHostKeyPrompt(message, promptProfileId, testOnly)
                if (!testOnly && "dpi_hint=possible" in message) monitorHandler.post { handleRuntimeDiagnostic(promptProfileId, message) }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(uiText(if (hostKeyPrompt != null) R.string.ssh_key_approval else if (isRunning) R.string.status_connected else R.string.status_connecting_progress)))
            }
        if (!started) {
            establishedTunnel.close()
            if (isStartCurrent(generation)) {
                isRunning = false
                handleStartFailure(testOnly, "Native proxy core failed to start", failureDetail, promptProfileId)
            }
            return false
        }
        val committed = synchronized(tunnelStateLock) {
            if (!isStartCurrent(generation) || tunnel != null) false else {
                tunnel = establishedTunnel
                core = proxyCore
                tunnelTestOnly = testOnly
                activeConfig = config
                isRunning = true
                true
            }
        }
        if (!committed) {
            proxyCore.stop()
            establishedTunnel.close()
            return false
        }
        underlyingNetwork?.let { setUnderlyingNetworks(arrayOf(it)) }
        probableFailureCounts.remove(promptProfileId)
        probableFailureTimes.remove(promptProfileId)
        if (failoverNotice == null) VpnRuntimeState.updateNetworkWarning(null)
        else VpnRuntimeState.updateNetworkWarning(failoverNotice)
        VpnRuntimeState.updateSystem(isAlwaysOnMode, isLockdownMode, promptProfileId)
        if (!testOnly) configStore.clearPendingReconnect(pendingReconnectToken)
        resetRetryState()
        VpnRuntimeState.update(VpnConnectionState.CONNECTED)
        return true
    }

    private fun isStartCurrent(generation: Long): Boolean = startGeneration.get() == generation

    private fun handleStartFailure(testOnly: Boolean, message: String, detail: String = message, profileId: String = "") {
        if (testOnly) {
            TestDiagnosticLog.fail(message)
            if (hostKeyPrompt != null) {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID, notification(this@ProxyVpnService.uiText(R.string.ssh_key_approval)),
                )
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        } else {
            val signal = BlockingDetection.classify(detail)
            if (signal != null && profileId.isNotEmpty()) {
                val count = recordProbableFailure(profileId)
                DiagnosticLog.add("event=blocking_detection result=suspected signal=${signal.name.lowercase()} consecutive=$count network=${networkKind()} profile_type=${ConfigStore(this).profile(profileId)?.config?.type?.name?.lowercase() ?: "unknown"}")
                if (count >= 2) handleProbableBlocking(profileId, signal)
            }
            if (signal == null && requiresUserAction(detail)) {
                val notice = if (isAlwaysOnMode)
                    this@ProxyVpnService.uiText(R.string.vpn_auth_always_on)
                else this@ProxyVpnService.uiText(R.string.vpn_auth_attention)
                VpnRuntimeState.updateNetworkWarning(notice)
                if (isAlwaysOnMode) {
                    connectionBlockedForAction = true
                    VpnRuntimeState.update(VpnConnectionState.CONNECTING)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(notice))
                } else {
                    ConfigStore(this).setConnectionDesired(false)
                    VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return
            }
            val failureStage = connectionFailureStage(detail)
            consecutiveStartFailures++
            if (!isAlwaysOnMode && consecutiveStartFailures >= MAX_MANUAL_START_FAILURES) {
                val notice = this@ProxyVpnService.uiText(R.string.vpn_retries_stopped, failureStage, consecutiveStartFailures)
                DiagnosticLog.add("event=vpn_retry result=exhausted attempts=$consecutiveStartFailures stage=${failureStageToken(detail)}")
                retryStatus = null
                ConfigStore(this).setConnectionDesired(false)
                VpnRuntimeState.updateNetworkWarning(notice)
                VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(notice))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            val delay = retryDelayMs(consecutiveStartFailures)
            nextStartAttemptAt = SystemClock.elapsedRealtime() + delay
            retryStatus = this@ProxyVpnService.uiText(R.string.vpn_retry_delay, failureStage, delay / 1_000, consecutiveStartFailures + 1)
            DiagnosticLog.add("event=vpn_retry result=scheduled attempt=${consecutiveStartFailures + 1} delay_ms=$delay stage=${failureStageToken(detail)}")
            VpnRuntimeState.update(VpnConnectionState.CONNECTING)
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification(retryStatus!!),
            )
        }
    }

    private fun retryDelayMs(failures: Int): Long = RETRY_DELAYS_MS[
        (failures - 1).coerceIn(0, RETRY_DELAYS_MS.lastIndex)
    ]

    private fun resetRetryState() {
        consecutiveStartFailures = 0
        nextStartAttemptAt = 0L
        retryStatus = null
    }

    private fun connectionFailureStage(detail: String): String = when {
        "SSH jump handshake" in detail -> this@ProxyVpnService.uiText(R.string.vpn_jump_handshake)
        "SSH destination handshake" in detail -> this@ProxyVpnService.uiText(R.string.vpn_destination_handshake)
        "jump host could not reach" in detail -> this@ProxyVpnService.uiText(R.string.vpn_jump_unreachable)
        "dial jump host" in detail -> this@ProxyVpnService.uiText(R.string.vpn_jump_connect)
        "VPN interface could not be established" in detail -> this@ProxyVpnService.uiText(R.string.vpn_interface_failed)
        else -> this@ProxyVpnService.uiText(R.string.vpn_connection_failed)
    }

    private fun failureStageToken(detail: String): String = when {
        "SSH jump handshake" in detail -> "ssh_jump_handshake"
        "SSH destination handshake" in detail -> "ssh_destination_handshake"
        "jump host could not reach" in detail -> "ssh_jump_direct_tcpip"
        "dial jump host" in detail -> "ssh_jump_tcp_connect"
        "VPN interface could not be established" in detail -> "vpn_establish"
        else -> "other"
    }

    private fun requiresUserAction(detail: String): Boolean {
        val value = detail.lowercase()
        return listOf(
            "authenticate", "authentication", "credentials", "unauthorized", "forbidden",
            "certificate", "x509", "host key", "ssh_host_key", "invalid config",
        ).any(value::contains)
    }

    @Synchronized
    private fun handleProbableBlocking(profileId: String, signal: BlockingSignal) {
        val store = ConfigStore(this)
        val settings = store.globalConnectionSettings()
        val warning = uiText(R.string.vpn_probably_blocked, uiText(signal.titleRes))
        VpnRuntimeState.updateNetworkWarning(warning)
        // DNS failover may move a hostname away from a filtered or stale address. Give
        // the current profile one fresh resolution attempt before changing exit IP or
        // location through profile failover.
        if (freshDnsRecoveryProfiles.add(profileId)) {
            store.profile(profileId)?.config?.let { profile ->
                BootstrapAddressCache(this).apply {
                    remove(profile.host)
                    if (profile.type.hasJump) remove(profile.jumpHost)
                }
            }
            val notice = this@ProxyVpnService.uiText(R.string.vpn_refresh_dns, warning)
            VpnRuntimeState.updateNetworkWarning(notice)
            DiagnosticLog.add("event=blocking_recovery action=refresh_dns profile_type=${store.profile(profileId)?.config?.type?.name?.lowercase() ?: "unknown"}")
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(notice))
            if (tunnel != null) stopTunnel(removeForeground = false)
            return
        }
        if (settings.failoverMode == FailoverMode.DISABLED) {
            val notice = this@ProxyVpnService.uiText(R.string.vpn_failover_disabled, warning)
            VpnRuntimeState.updateNetworkWarning(notice)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(notice))
            return
        }
        val ordered = store.sortedProfiles()
        attemptedFailoverProfiles += profileId
        val currentIndex = ordered.indexOfFirst { it.id == profileId }.coerceAtLeast(0)
        val afterCurrent = ordered.drop(currentIndex + 1) + ordered.take(currentIndex + 1)
        val candidates = when (settings.failoverMode) {
            FailoverMode.SELECTED -> afterCurrent.filter { it.id in settings.failoverProfileIds }
            FailoverMode.ALL -> afterCurrent
            else -> emptyList()
        }.filter { it.id != profileId && it.id !in attemptedFailoverProfiles }
        val next = candidates.firstOrNull() ?: run {
            val notice = this@ProxyVpnService.uiText(R.string.vpn_failover_empty, warning)
            failoverNotice = notice
            store.setFailoverState(false, notice)
            VpnRuntimeState.updateNetworkWarning(notice)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(notice))
            return
        }
        attemptedFailoverProfiles += next.id
        resetRetryState()
        store.setConnectionProfile(next.id)
        val notice = this@ProxyVpnService.uiText(R.string.vpn_failover_active, next.localizedName(this))
        failoverNotice = notice
        store.setFailoverState(true, notice)
        VpnRuntimeState.updateNetworkWarning(notice)
        VpnRuntimeState.updateSystem(isAlwaysOnMode, isLockdownMode, next.id)
        if (tunnel != null) stopTunnel(removeForeground = false)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(notice))
    }

    private fun handleRuntimeDiagnostic(profileId: String, detail: String) {
        val signal = BlockingDetection.classify(detail) ?: return
        val count = recordProbableFailure(profileId)
        if (count >= 2) handleProbableBlocking(profileId, signal)
    }

    private fun recordProbableFailure(profileId: String): Int {
        val now = SystemClock.elapsedRealtime()
        val previous = probableFailureTimes.put(profileId, now)
        return if (previous == null || now - previous > FAILURE_WINDOW_MS) {
            probableFailureCounts[profileId] = 1
            1
        } else {
            probableFailureCounts.merge(profileId, 1, Int::plus) ?: 1
        }
    }

    private fun networkKind(): String {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "none"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun updatePassiveHealthState() {
        if (!isRunning) return
        // The monitor runs on the main looper. Avoid JNI/reflection/JSON work here;
        // health evaluation itself is safe on this short-lived worker.
        thread(name = "megaproxy-health-snapshot") {
            updatePassiveHealthStateOffMain()
        }
    }

    private fun updatePassiveHealthStateOffMain() {
        val stats = ConnectionStatsReader.snapshot() ?: return
        val bytes = stats.downloadBytes + stats.uploadBytes
        val trafficProgressed = bytes > lastHealthBytes
        val newConnectionOutcomes = stats.totalOutcomes > lastHealthOutcomes
        when {
            trafficProgressed && healthWarningActive -> {
                healthWarningActive = false
                if (failoverNotice == null) VpnRuntimeState.updateNetworkWarning(null)
                DiagnosticLog.add("event=connection_health state=recovered")
            }
            newConnectionOutcomes && stats.connectionSamples >= 3 && stats.connectionErrorRate >= 0.75 && !healthWarningActive -> {
                healthWarningActive = true
                val warning = this@ProxyVpnService.uiText(R.string.vpn_degraded)
                VpnRuntimeState.updateNetworkWarning(warning)
                DiagnosticLog.add("event=connection_health state=degraded error_rate_percent=${(stats.connectionErrorRate * 100).toInt()} samples=${stats.connectionSamples}")
            }
        }
        lastHealthBytes = bytes
        lastHealthOutcomes = stats.totalOutcomes
    }

    private fun registerUnderlyingNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            manager.registerNetworkCallback(request, networkCallback)
            networkCallbackRegistered = true
        }.onFailure {
            DiagnosticLog.add("event=network_monitor result=failed reason=${it.javaClass.simpleName}")
        }
    }

    private fun reconsiderUnderlyingNetworks(preferred: Network? = null) {
        monitorHandler.post {
            val validated = underlyingCapabilities.entries
                .filter { it.value.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
                .maxWithOrNull(
                    compareBy<Map.Entry<Network, NetworkCapabilities>> { networkPreference(it.value) }
                        .thenBy { if (it.key == preferred) 1 else 0 }
                        .thenBy { if (it.key == underlyingNetwork) 1 else 0 },
                )?.key
            val captive = underlyingCapabilities.values.any {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            }
            if (validated == null) {
                if (captive) {
                    val warning = this@ProxyVpnService.uiText(R.string.vpn_wifi_login)
                    VpnRuntimeState.updateNetworkWarning(warning)
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(warning))
                    DiagnosticLog.add("event=network state=captive_portal")
                }
                return@post
            }
            val previous = underlyingNetwork
            if (previous == validated) return@post
            underlyingNetwork = validated
            if (tunnel != null) setUnderlyingNetworks(arrayOf(validated))
            DiagnosticLog.add("event=network underlying=changed connected=${tunnel != null}")
            if (previous != null && tunnel != null) {
                monitorHandler.removeCallbacks(reconnectForNetworkChange)
                monitorHandler.postDelayed(reconnectForNetworkChange, NETWORK_CHANGE_DEBOUNCE_MS)
            }
        }
    }

    private fun networkPreference(capabilities: NetworkCapabilities): Int = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
        else -> 0
    }

    private fun stopTunnel(removeForeground: Boolean = true) {
        startGeneration.incrementAndGet()
        val stopped = synchronized(tunnelStateLock) {
            val result = Triple(tunnel, core, tunnelTestOnly)
            tunnel = null
            core = null
            tunnelTestOnly = false
            activeConfig = null
            isRunning = false
            healthWarningActive = false
            lastHealthBytes = 0L
            lastHealthOutcomes = 0L
            result
        }
        if (stopped.first != null) {
            if (stopped.third) TestDiagnosticLog.add("Stopping temporary VPN")
            else DiagnosticLog.add("Stopping VPN")
        }
        VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
        resetRetryState()
        stopped.second?.stop()
        stopped.first?.close()
        if (removeForeground) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        ConfigStore(this).setConnectionDesired(false)
        isAlwaysOnMode = false
        isLockdownMode = false
        stopTunnel()
        stopSelf()
    }
    override fun onDestroy() {
        monitorHandler.removeCallbacks(monitor)
        monitorHandler.removeCallbacks(reconnectForNetworkChange)
        if (networkCallbackRegistered) {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        }
        stopTunnel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW).apply {
            description = this@ProxyVpnService.uiText(R.string.vpn_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun configureHostKeyPrompt(message: String, profileId: String, testOnly: Boolean) {
        val marker = when {
            "SSH_HOST_KEY_UNKNOWN|" in message -> "SSH_HOST_KEY_UNKNOWN|"
            "SSH_HOST_KEY_CHANGED|" in message -> "SSH_HOST_KEY_CHANGED|"
            else -> return
        }
        val parts = message.substringAfter(marker).substringBefore(' ').split('|')
        if (parts.size < 3) return
        val changed = marker.startsWith("SSH_HOST_KEY_CHANGED")
        val fingerprint = if (changed) parts.getOrNull(3) else parts.getOrNull(2)
        if (fingerprint == null || !fingerprint.startsWith("SHA256:")) return
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_REVIEW_SSH_HOST_KEY)
            .putExtra(MainActivity.EXTRA_PROFILE_ID, profileId)
            .putExtra(MainActivity.EXTRA_HOP, parts[0])
            .putExtra(MainActivity.EXTRA_ALGORITHM, parts[1])
            .putExtra(MainActivity.EXTRA_FINGERPRINT, fingerprint)
            .putExtra(MainActivity.EXTRA_CHANGED, changed)
            .putExtra(MainActivity.EXTRA_TEST_ONLY, testOnly)
        SshHostKeyPromptState.show(
            PendingSshHostKey(profileId, parts[0], parts[1], fingerprint, changed, testOnly),
        )
        hostKeyPrompt = PendingIntent.getActivity(
            this, profileId.hashCode() xor parts[0].hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(net.megaproxy487.R.drawable.ic_vpn_notification)
        .setContentTitle(uiText(R.string.megaproxy_active))
        .setContentText(text)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(hostKeyPrompt ?: PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .also { builder -> hostKeyPrompt?.let { builder.addAction(0, uiText(R.string.review_ssh_key), it) } }
        .build()

    companion object {
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 101
        private const val ACTION_STOP = "net.megaproxy487.STOP"
        private const val ACTION_TEST = "net.megaproxy487.TEST"
        private const val ACTION_RECONNECT = "net.megaproxy487.RECONNECT"
        private const val ACTION_START_MANUAL = "net.megaproxy487.START_MANUAL"
        private const val ACTION_REFRESH_STATUS = "net.megaproxy487.REFRESH_STATUS"
        private const val ACTION_DISMISS_HOST_KEY = "net.megaproxy487.DISMISS_HOST_KEY"
        private const val EXTRA_RECONNECT_PROFILE_ID = "reconnect_profile_id"
        private const val EXTRA_RECONNECT_REASON = "reconnect_reason"
        private const val MONITOR_INTERVAL_MS = 10_000L
        private const val FAILURE_WINDOW_MS = 60_000L
        private const val NETWORK_CHANGE_DEBOUNCE_MS = 1_500L
        private const val MAX_MANUAL_START_FAILURES = 5
        private const val VPN_MTU = 1_400
        private val RETRY_DELAYS_MS = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 180_000L, 300_000L)
        private val testRunning = AtomicBoolean(false)
        private val startRunning = AtomicBoolean(false)
        private val commandScope = CoroutineScope(SupervisorJob() + ConfigIoDispatcher)
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var isAlwaysOnMode: Boolean = false
            private set
        @Volatile var isLockdownMode: Boolean = false
            private set

        fun start(context: Context) {
            val app = context.applicationContext
            commandScope.launch {
                ConfigStore(app).setConnectionDesired(true)
                ConfigStore(app).let { it.setConnectionProfile(it.activeProfileId()) }
                ContextCompat.startForegroundService(app, Intent(app, ProxyVpnService::class.java).setAction(ACTION_START_MANUAL))
            }
        }
        fun stop(context: Context) {
            val app = context.applicationContext
            commandScope.launch {
                ConfigStore(app).setConnectionDesired(false)
                app.startService(Intent(app, ProxyVpnService::class.java).setAction(ACTION_STOP))
            }
        }
        fun reconnect(context: Context) {
            val app = context.applicationContext
            commandScope.launch {
                ConfigStore(app).setConnectionDesired(true)
                ContextCompat.startForegroundService(app, Intent(app, ProxyVpnService::class.java).setAction(ACTION_RECONNECT))
            }
        }
        /** Called from MY_PACKAGE_REPLACED after its receiver has checked persisted state. */
        fun restoreAfterPackageUpdate(context: Context) {
            val app = context.applicationContext
            ContextCompat.startForegroundService(
                app,
                Intent(app, ProxyVpnService::class.java).setAction(ACTION_RECONNECT)
                    .putExtra(EXTRA_RECONNECT_REASON, "package_replaced"),
            )
        }
        fun switchProfile(context: Context, profileId: String, useAsAlwaysOn: Boolean) {
            val app = context.applicationContext
            commandScope.launch {
                val store = ConfigStore(app)
                if (store.profile(profileId) == null) return@launch
                if (useAsAlwaysOn) store.setAlwaysOnProfile(profileId) else store.setActiveProfile(profileId)
                store.setConnectionProfile(profileId)
                store.setConnectionDesired(true)
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, ProxyVpnService::class.java).setAction(ACTION_RECONNECT)
                        .putExtra(EXTRA_RECONNECT_PROFILE_ID, profileId)
                        .putExtra(EXTRA_RECONNECT_REASON, "profile_changed"),
                )
            }
        }
        fun test(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, ProxyVpnService::class.java).setAction(ACTION_TEST),
        )
        fun refreshStatus(context: Context) {
            if (isRunning) {
                context.startService(Intent(context, ProxyVpnService::class.java).setAction(ACTION_REFRESH_STATUS))
            }
        }
        fun dismissHostKeyPrompt(context: Context) {
            SshHostKeyPromptState.clear()
            context.startService(Intent(context, ProxyVpnService::class.java).setAction(ACTION_DISMISS_HOST_KEY))
        }
    }
}
