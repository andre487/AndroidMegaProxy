package dev.megaproxy.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.megaproxy.app.MainActivity
import dev.megaproxy.app.data.ConfigStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ProxyVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var core: ProxyCore? = null
    private var activeConfig: dev.megaproxy.app.model.ProxyConfig? = null
    private var tunnelTestOnly = false
    private val monitorHandler = Handler(Looper.getMainLooper())
    private val monitor = object : Runnable {
        override fun run() {
            val desired = ConfigStore(this@ProxyVpnService).isConnectionDesired()
            if (desired) {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(if (isRunning) "Connected" else "Reconnecting…"),
                )
                if (tunnel == null && !testRunning.get() && startRunning.compareAndSet(false, true)) {
                    VpnRuntimeState.update(VpnConnectionState.CONNECTING)
                    thread(name = "megaproxy-vpn-recovery") {
                        try {
                            startTunnel(testOnly = false)
                        } finally {
                            startRunning.set(false)
                        }
                    }
                }
            }
            monitorHandler.postDelayed(this, MONITOR_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        monitorHandler.post(monitor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isAlwaysOnMode = isAlwaysOn
            isLockdownMode = isLockdownEnabled
        }
        val store = ConfigStore(this)
        if (isAlwaysOnMode && intent?.action != ACTION_START_MANUAL) {
            store.setConnectionDesired(true)
            store.setConnectionProfile(store.alwaysOnProfileId())
        }
        if (intent?.action == ACTION_START_MANUAL) {
            store.setConnectionProfile(store.activeProfileId())
        }
        VpnRuntimeState.updateSystem(isAlwaysOnMode, isLockdownMode, store.connectionProfile().id)
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TEST) {
            startForeground(NOTIFICATION_ID, notification("Testing connection…"))
            TestDiagnosticLog.begin()
            thread(name = "megaproxy-connection-test") { testConnection() }
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Connecting…"))
        if (tunnel == null && startRunning.compareAndSet(false, true)) {
            VpnRuntimeState.update(VpnConnectionState.CONNECTING)
            thread(name = "megaproxy-vpn-start") {
                try {
                    startTunnel(testOnly = false)
                } finally {
                    startRunning.set(false)
                }
            }
        }
        return START_STICKY
    }

    private fun testConnection() {
        if (!testRunning.compareAndSet(false, true)) {
            TestDiagnosticLog.add("Connection test is already running")
            return
        }
        val storedConfig = ConfigStore(this).activeProfile().config
        storedConfig.connectionValidationError()?.let {
            TestDiagnosticLog.fail("Connection test cannot start: $it")
            testRunning.set(false)
            if (tunnel == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return
        }
        val temporaryVpn = tunnel == null
        if (temporaryVpn && !startTunnel(testOnly = true, suppliedConfig = storedConfig)) {
            TestDiagnosticLog.fail("Connection test failed: temporary VPN could not be started")
            testRunning.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val config = activeConfig ?: run {
            TestDiagnosticLog.fail("Connection test failed: active VPN configuration is unavailable")
            testRunning.set(false)
            return
        }
        val exitIp = NativeProxyCore(this, TestDiagnosticLog::add).test(config) { message ->
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
        }
        if (exitIp != null) TestDiagnosticLog.succeed(exitIp) else TestDiagnosticLog.fail()
        testRunning.set(false)
        if (temporaryVpn) {
            stopTunnel()
            stopSelf()
        }
    }

    private fun startTunnel(testOnly: Boolean, suppliedConfig: dev.megaproxy.app.model.ProxyConfig? = null): Boolean {
        val storedProfile = ConfigStore(this).connectionProfile()
        val storedConfig = suppliedConfig ?: storedProfile.config
        val diagnostics = if (testOnly) TestDiagnosticLog::add else DiagnosticLog::add
        diagnostics(
            if (testOnly) "Starting temporary VPN for connection test"
            else if (storedConfig.routeAllApps) "Starting global VPN with profile ${storedProfile.displayName}"
            else "Starting VPN with profile ${storedProfile.displayName} for ${storedConfig.selectedPackages.size} selected application(s)"
        )
        val validationError = if (testOnly) storedConfig.connectionValidationError() else storedConfig.validationError()
        validationError?.let {
            if (testOnly) TestDiagnosticLog.fail(it) else {
                ConfigStore(this).setConnectionDesired(false)
                VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        val allowedPackages = when {
            testOnly -> setOf(packageName)
            storedConfig.routeAllApps -> emptySet()
            else -> storedConfig.selectedPackages
        }
        allowedPackages.forEach { packageName ->
            runCatching { builder.addAllowedApplication(packageName) }
        }
        tunnel = builder.establish() ?: run {
            handleStartFailure(testOnly, "VPN interface could not be established")
            return false
        }
        tunnelTestOnly = testOnly
        diagnostics("TUN established with IPv4, IPv6 and intercepted DNS")
        val proxyCore = NativeProxyCore(this, diagnostics)
        val proxyIp = proxyCore.resolveProxy(storedConfig.host) { message ->
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
        } ?: run {
            tunnel?.close()
            tunnel = null
            handleStartFailure(testOnly, "Proxy bootstrap DNS failed")
            return false
        }
        val config = storedConfig.copy(resolvedProxyIp = proxyIp)
        core = proxyCore.also {
            val started = it.start(tunnel!!.fd, config) { message ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
            }
            if (!started) {
                tunnel?.close()
                tunnel = null
                core = null
                isRunning = false
                handleStartFailure(testOnly, "Native proxy core failed to start")
                return false
            }
        }
        isRunning = true
        VpnRuntimeState.update(VpnConnectionState.CONNECTED)
        activeConfig = config
        return true
    }

    private fun handleStartFailure(testOnly: Boolean, message: String) {
        if (testOnly) {
            TestDiagnosticLog.fail(message)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            DiagnosticLog.add("$message; retrying")
            VpnRuntimeState.update(VpnConnectionState.CONNECTING)
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("Connection failed; retrying…"),
            )
        }
    }

    private fun stopTunnel() {
        if (tunnel != null) {
            if (tunnelTestOnly) TestDiagnosticLog.add("Stopping temporary VPN")
            else DiagnosticLog.add("Stopping VPN")
        }
        isRunning = false
        VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
        core?.stop()
        core = null
        tunnel?.close()
        tunnel = null
        tunnelTestOnly = false
        activeConfig = null
        stopForeground(STOP_FOREGROUND_REMOVE)
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

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle("MegaProxy is active")
        .setContentText(text)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 101
        private const val ACTION_STOP = "dev.megaproxy.STOP"
        private const val ACTION_TEST = "dev.megaproxy.TEST"
        private const val ACTION_START_MANUAL = "dev.megaproxy.START_MANUAL"
        private const val MONITOR_INTERVAL_MS = 10_000L
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
        fun test(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, ProxyVpnService::class.java).setAction(ACTION_TEST),
        )
    }
}
