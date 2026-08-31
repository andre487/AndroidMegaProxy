package dev.megaproxy.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
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

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isAlwaysOnMode = isAlwaysOn
            isLockdownMode = isLockdownEnabled
        }
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TEST) {
            startForeground(NOTIFICATION_ID, notification("Testing connection…"))
            thread(name = "megaproxy-connection-test") { testConnection() }
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("Connecting…"))
        if (tunnel == null && startRunning.compareAndSet(false, true)) {
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
            DiagnosticLog.add("Connection test is already running")
            return
        }
        val storedConfig = ConfigStore(this).load()
        storedConfig.connectionValidationError()?.let {
            DiagnosticLog.add("Connection test cannot start: $it")
            testRunning.set(false)
            if (tunnel == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            return
        }
        val temporaryVpn = tunnel == null
        if (temporaryVpn && !startTunnel(testOnly = true, suppliedConfig = storedConfig)) {
            DiagnosticLog.add("Connection test failed: temporary VPN could not be started")
            testRunning.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val config = activeConfig ?: run {
            DiagnosticLog.add("Connection test failed: active VPN configuration is unavailable")
            testRunning.set(false)
            return
        }
        NativeProxyCore(this).test(config) { message ->
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
        }
        testRunning.set(false)
        if (temporaryVpn) {
            stopTunnel()
            stopSelf()
        }
    }

    private fun startTunnel(testOnly: Boolean, suppliedConfig: dev.megaproxy.app.model.ProxyConfig? = null): Boolean {
        val storedConfig = suppliedConfig ?: ConfigStore(this).load()
        DiagnosticLog.add(if (testOnly) "Starting temporary VPN for connection test" else "Starting VPN for ${storedConfig.selectedPackages.size} selected application(s)")
        val validationError = if (testOnly) storedConfig.connectionValidationError() else storedConfig.validationError()
        validationError?.let {
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
        val allowedPackages = if (testOnly) setOf(packageName) else storedConfig.selectedPackages
        allowedPackages.forEach { packageName ->
            runCatching { builder.addAllowedApplication(packageName) }
        }
        tunnel = builder.establish() ?: run { stopSelf(); return false }
        DiagnosticLog.add("TUN established with IPv4, IPv6 and intercepted DNS")
        val proxyCore = NativeProxyCore(this)
        val proxyIp = proxyCore.resolveProxy(storedConfig.host) { message ->
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
        } ?: run {
            tunnel?.close()
            tunnel = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return false
            }
        }
        isRunning = true
        activeConfig = config
        return true
    }

    private fun stopTunnel() {
        if (tunnel != null) DiagnosticLog.add("Stopping VPN")
        isRunning = false
        core?.stop()
        core = null
        tunnel?.close()
        tunnel = null
        activeConfig = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        isAlwaysOnMode = false
        isLockdownMode = false
        stopTunnel()
        stopSelf()
    }
    override fun onDestroy() { stopTunnel(); super.onDestroy() }

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
        private val testRunning = AtomicBoolean(false)
        private val startRunning = AtomicBoolean(false)
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var isAlwaysOnMode: Boolean = false
            private set
        @Volatile var isLockdownMode: Boolean = false
            private set

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, ProxyVpnService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, ProxyVpnService::class.java).setAction(ACTION_STOP))
        fun test(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, ProxyVpnService::class.java).setAction(ACTION_TEST),
        )
    }
}
