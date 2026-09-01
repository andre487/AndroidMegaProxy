package net.megaproxy487.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import net.megaproxy487.MainActivity
import net.megaproxy487.SshHostKeyActivity
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.model.FailoverMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class ProxyVpnService : VpnService() {
    @Volatile private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var core: ProxyCore? = null
    @Volatile private var activeConfig: net.megaproxy487.model.ProxyConfig? = null
    @Volatile private var tunnelTestOnly = false
    @Volatile private var hostKeyPrompt: PendingIntent? = null
    @Volatile private var failoverNotice: String? = null
    @Volatile private var connectionBlockedForAction = false
    @Volatile private var reconnectAfterStart = false
    private val tunnelStateLock = Any()
    private val startGeneration = AtomicLong(0)
    private val probableFailureCounts = ConcurrentHashMap<String, Int>()
    private val probableFailureTimes = ConcurrentHashMap<String, Long>()
    private val attemptedFailoverProfiles = ConcurrentHashMap.newKeySet<String>()
    private val monitorHandler = Handler(Looper.getMainLooper())
    private val monitor = object : Runnable {
        override fun run() {
            val desired = ConfigStore(this@ProxyVpnService).isConnectionDesired()
            if (desired) {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(failoverNotice ?: VpnRuntimeState.networkWarning.value ?: if (isRunning) "Connected" else "Reconnecting…"),
                )
                if (tunnel == null && hostKeyPrompt == null && !connectionBlockedForAction && !testRunning.get() && startRunning.compareAndSet(false, true)) {
                    val generation = startGeneration.get()
                    VpnRuntimeState.update(VpnConnectionState.CONNECTING)
                    thread(name = "megaproxy-vpn-recovery") {
                        try {
                            startTunnel(testOnly = false, generation = generation)
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
        monitorHandler.post(monitor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val systemAlwaysOnStart = intent?.action == SERVICE_INTERFACE
        val preserveKnownStatus = intent?.action == ACTION_REFRESH_STATUS && isAlwaysOnMode
        // Android starts the selected service with SERVICE_INTERFACE on older releases;
        // the explicit isAlwaysOn property itself was only added in API 29.
        val platformAlwaysOn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAlwaysOn
        isAlwaysOnMode = platformAlwaysOn || systemAlwaysOnStart || preserveKnownStatus
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
            store.setConnectionProfile(store.activeProfileId())
            probableFailureCounts.clear(); probableFailureTimes.clear(); attemptedFailoverProfiles.clear(); failoverNotice = null
            connectionBlockedForAction = false
            store.setFailoverState(false, null)
            VpnRuntimeState.updateNetworkWarning(null)
        }
        VpnRuntimeState.updateSystem(isAlwaysOnMode, isLockdownMode, store.connectionProfile().id)
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
            intent.getStringExtra(EXTRA_RECONNECT_PROFILE_ID)?.let(store::setConnectionProfile)
            hostKeyPrompt = null; failoverNotice = null
            connectionBlockedForAction = false
            store.setFailoverState(false, null)
            probableFailureCounts.clear(); probableFailureTimes.clear(); attemptedFailoverProfiles.clear()
            VpnRuntimeState.updateNetworkWarning(null)
            DiagnosticLog.add("event=vpn_reconnect reason=${intent.getStringExtra(EXTRA_RECONNECT_REASON) ?: "settings_changed"}")
            startForeground(NOTIFICATION_ID, notification("Reconnecting…"))
            stopTunnel(removeForeground = false)
            val generation = startGeneration.get()
            ConfigStore(this).setConnectionDesired(true)
            if (startRunning.compareAndSet(false, true)) {
                VpnRuntimeState.update(VpnConnectionState.CONNECTING)
                thread(name = "megaproxy-vpn-reconnect") {
                    try {
                        startTunnel(testOnly = false, generation = generation)
                    } finally {
                        finishStartAttempt()
                    }
                }
            } else reconnectAfterStart = true
            return START_STICKY
        }
        if (intent?.action == ACTION_TEST) {
            hostKeyPrompt = null
            SshHostKeyPromptState.clear()
            startForeground(NOTIFICATION_ID, notification("Testing connection…"))
            TestDiagnosticLog.begin()
            thread(name = "megaproxy-connection-test") { testConnection() }
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Connecting…"))
        if (tunnel == null && startRunning.compareAndSet(false, true)) {
            val generation = startGeneration.get()
            VpnRuntimeState.update(VpnConnectionState.CONNECTING)
            thread(name = "megaproxy-vpn-start") {
                try {
                    startTunnel(testOnly = false, generation = generation)
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
        if (!testRunning.compareAndSet(false, true)) {
            TestDiagnosticLog.add("Connection test is already running")
            return
        }
        val storedConfig = ConfigStore(this).globalConnectionSettings().applyTo(ConfigStore(this).activeProfile().config)
        storedConfig.connectionValidationError()?.let {
            TestDiagnosticLog.fail("Connection test cannot start: $it")
            testRunning.set(false)
            if (tunnel == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return
        }
        val temporaryVpn = tunnel == null
        if (temporaryVpn && !startTunnel(testOnly = true, suppliedConfig = storedConfig, generation = startGeneration.get())) {
            TestDiagnosticLog.fail("Connection test failed: temporary VPN could not be started")
            testRunning.set(false)
            if (hostKeyPrompt == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        val config = synchronized(tunnelStateLock) { activeConfig } ?: run {
            TestDiagnosticLog.fail("Connection test failed: active VPN configuration is unavailable")
            testRunning.set(false)
            return
        }
        val exitIp = NativeProxyCore(this, TestDiagnosticLog::add).test(config) { message ->
            configureHostKeyPrompt(message, ConfigStore(this).activeProfileId(), true)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
        }
        if (exitIp != null) TestDiagnosticLog.succeed(exitIp) else TestDiagnosticLog.fail()
        testRunning.set(false)
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
        val storedProfile = ConfigStore(this).connectionProfile()
        val storedConfig = suppliedConfig ?: ConfigStore(this).globalConnectionSettings().applyTo(storedProfile.config)
        val promptProfileId = if (testOnly) ConfigStore(this).activeProfileId() else storedProfile.id
        val diagnostics = if (testOnly) TestDiagnosticLog::add else DiagnosticLog::add
        var failureDetail = ""
        diagnostics(
            if (testOnly) "event=vpn_start mode=test"
            else if (storedConfig.routeAllApps) "event=vpn_start mode=global"
            else "event=vpn_start mode=split selected_app_count=${storedConfig.selectedPackages.size}"
        )
        val validationError = if (testOnly) storedConfig.connectionValidationError() else storedConfig.validationError()
        validationError?.let {
            if (testOnly) TestDiagnosticLog.fail(it) else {
                val notice = if (isAlwaysOnMode)
                    "VPN configuration requires attention. Fix it in MegaProxy, then reconnect from Android Always-on VPN settings."
                else "VPN configuration requires attention. Open MegaProxy settings."
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
            .setMtu(1500)
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
        val proxyIp = proxyCore.resolveProxy(storedConfig.host) { message ->
            failureDetail = message
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
        } ?: run {
            establishedTunnel.close()
            if (!isStartCurrent(generation)) return false
            handleStartFailure(testOnly, "Proxy bootstrap DNS failed", failureDetail, promptProfileId)
            return false
        }
        val jumpIp = if (storedConfig.type == net.megaproxy487.model.ProxyType.SSH_JUMP) {
            proxyCore.resolveProxy(storedConfig.jumpHost) { message ->
                failureDetail = message
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
            } ?: run {
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
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
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
        probableFailureCounts.remove(promptProfileId)
        probableFailureTimes.remove(promptProfileId)
        if (failoverNotice == null) VpnRuntimeState.updateNetworkWarning(null)
        else VpnRuntimeState.updateNetworkWarning(failoverNotice)
        VpnRuntimeState.updateSystem(isAlwaysOnMode, isLockdownMode, promptProfileId)
        VpnRuntimeState.update(VpnConnectionState.CONNECTED)
        return true
    }

    private fun isStartCurrent(generation: Long): Boolean = startGeneration.get() == generation

    private fun handleStartFailure(testOnly: Boolean, message: String, detail: String = message, profileId: String = "") {
        if (testOnly) {
            TestDiagnosticLog.fail(message)
            if (hostKeyPrompt != null) {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID, notification("SSH host key approval required"),
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
                    "VPN connection requires attention. Check authentication, certificate, or SSH host-key settings, then reconnect Always-on VPN."
                else "VPN connection requires attention. Check authentication, certificate, or SSH host-key settings."
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
            DiagnosticLog.add("$message; retrying")
            VpnRuntimeState.update(VpnConnectionState.CONNECTING)
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification(VpnRuntimeState.networkWarning.value ?: "Connection failed; retrying…"),
            )
        }
    }

    private fun requiresUserAction(detail: String): Boolean {
        val value = detail.lowercase()
        return listOf(
            "authenticate", "authentication", "credentials", "unauthorized", "forbidden",
            "certificate", "x509", "host key", "ssh_host_key", "invalid config",
        ).any(value::contains)
    }

    private fun handleProbableBlocking(profileId: String, signal: BlockingSignal) {
        val store = ConfigStore(this)
        val settings = store.globalConnectionSettings()
        val warning = "Proxy probably blocked (${signal.name.lowercase().replace('_', ' ')})."
        VpnRuntimeState.updateNetworkWarning(warning)
        if (settings.failoverMode == FailoverMode.DISABLED) {
            val notice = "$warning Failover is disabled; the connection may remain unavailable."
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
            failoverNotice = "$warning No eligible fallback profile remains."
            store.setFailoverState(false, failoverNotice)
            VpnRuntimeState.updateNetworkWarning(failoverNotice)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(failoverNotice!!))
            return
        }
        attemptedFailoverProfiles += next.id
        store.setConnectionProfile(next.id)
        failoverNotice = "Failover active: switched to ${next.displayName}. Location and exit IP may have changed."
        store.setFailoverState(true, failoverNotice)
        VpnRuntimeState.updateNetworkWarning(failoverNotice)
        VpnRuntimeState.updateSystem(isAlwaysOnMode, isLockdownMode, next.id)
        if (tunnel != null) stopTunnel(removeForeground = false)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(failoverNotice!!))
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

    private fun stopTunnel(removeForeground: Boolean = true) {
        startGeneration.incrementAndGet()
        val stopped = synchronized(tunnelStateLock) {
            val result = Triple(tunnel, core, tunnelTestOnly)
            tunnel = null
            core = null
            tunnelTestOnly = false
            activeConfig = null
            isRunning = false
            result
        }
        if (stopped.first != null) {
            if (stopped.third) TestDiagnosticLog.add("Stopping temporary VPN")
            else DiagnosticLog.add("Stopping VPN")
        }
        VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
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
        stopTunnel()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Persistent VPN status"
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
        val intent = Intent(this, SshHostKeyActivity::class.java)
            .putExtra(SshHostKeyActivity.EXTRA_PROFILE_ID, profileId)
            .putExtra(SshHostKeyActivity.EXTRA_HOP, parts[0])
            .putExtra(SshHostKeyActivity.EXTRA_ALGORITHM, parts[1])
            .putExtra(SshHostKeyActivity.EXTRA_FINGERPRINT, fingerprint)
            .putExtra(SshHostKeyActivity.EXTRA_CHANGED, changed)
            .putExtra(SshHostKeyActivity.EXTRA_TEST_ONLY, testOnly)
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
        .setContentTitle("MegaProxy is active")
        .setContentText(text)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(hostKeyPrompt ?: PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .also { builder -> hostKeyPrompt?.let { builder.addAction(0, "Review SSH key", it) } }
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
        private val testRunning = AtomicBoolean(false)
        private val startRunning = AtomicBoolean(false)
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var isAlwaysOnMode: Boolean = false
            private set
        @Volatile var isLockdownMode: Boolean = false
            private set

        fun start(context: Context) {
            ConfigStore(context).setConnectionDesired(true)
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProxyVpnService::class.java).setAction(ACTION_START_MANUAL),
            )
        }
        fun stop(context: Context) {
            ConfigStore(context).setConnectionDesired(false)
            context.startService(Intent(context, ProxyVpnService::class.java).setAction(ACTION_STOP))
        }
        fun reconnect(context: Context) {
            ConfigStore(context).setConnectionDesired(true)
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProxyVpnService::class.java).setAction(ACTION_RECONNECT),
            )
        }
        fun switchProfile(context: Context, profileId: String, useAsAlwaysOn: Boolean) {
            val store = ConfigStore(context)
            if (store.profile(profileId) == null) return
            if (useAsAlwaysOn) store.setAlwaysOnProfile(profileId) else store.setActiveProfile(profileId)
            store.setConnectionProfile(profileId)
            store.setConnectionDesired(true)
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProxyVpnService::class.java).setAction(ACTION_RECONNECT)
                    .putExtra(EXTRA_RECONNECT_PROFILE_ID, profileId)
                    .putExtra(EXTRA_RECONNECT_REASON, "profile_changed"),
            )
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
