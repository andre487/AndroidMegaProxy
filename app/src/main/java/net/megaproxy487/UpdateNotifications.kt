package net.megaproxy487

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

internal val UPDATE_REMINDER_INTERVAL = TimeUnit.DAYS.toMillis(7)
internal val UPDATE_RETRY_INTERVAL = TimeUnit.MINUTES.toMillis(30)
internal const val UPDATE_JOB_ID = 48702
internal const val UPDATE_NOTIFICATION_ID = 48702
internal const val UPDATE_NOTIFICATION_CHANNEL = "app_updates"
internal const val OPEN_UPDATES_ACTION = "net.megaproxy487.OPEN_UPDATES"
internal const val SKIP_UPDATE_ACTION = "net.megaproxy487.SKIP_UPDATE"
internal const val DISABLE_UPDATES_ACTION = "net.megaproxy487.DISABLE_UPDATES"

internal fun shouldNotifyUpdate(key: String, skipped: String?, lastKey: String?, lastTime: Long, now: Long): Boolean =
    key != skipped && (key != lastKey || now - lastTime >= UPDATE_REMINDER_INTERVAL)

internal class UpdatePreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply() }

    val lastBackgroundTime: Long get() = prefs.getLong("background_at", 0)
    val lastBackgroundResult: String? get() = prefs.getString("background_result", null)
    val lastBackgroundSource: String? get() = prefs.getString("background_source", null)

    fun backgroundStarted(source: UpdateSource, now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("background_at", now).putString("background_source", source.name)
            .putString("background_result", "running").apply()
    }

    fun backgroundFinished(result: String) {
        prefs.edit().putString("background_result", result).apply()
    }

    fun detected(source: UpdateSource, update: AppUpdate?) {
        if (AppUpdates(context).source() != source) return
        prefs.edit().apply {
            if (update == null) { remove("detected_source"); remove("detected_version") }
            else { putString("detected_source", source.name); putString("detected_version", update.version) }
        }.apply()
    }

    fun pending(now: Long = System.currentTimeMillis()): AppUpdate? {
        if (!automatic) return null
        val source = UpdateSource.entries.firstOrNull { it.name == prefs.getString("detected_source", null) } ?: return null
        val version = prefs.getString("detected_version", null) ?: return null
        val update = AppUpdate(source, version)
        if (AppUpdates(context).source() != source || !runCatching { newerVersion(version, BuildConfig.VERSION_NAME) }.getOrDefault(false)) return null
        if (key(update) in prefs.getStringSet("skipped_versions", emptySet()).orEmpty()) return null
        if (key(update) == prefs.getString("dialog_key", null) && now < prefs.getLong("dialog_after", 0)) return null
        return update
    }

    fun remindLater(update: AppUpdate, now: Long = System.currentTimeMillis()) {
        prefs.edit().putString("dialog_key", key(update)).putLong("dialog_after", now + UPDATE_REMINDER_INTERVAL)
            .putString("notified", key(update)).putLong("notified_at", now).apply()
    }

    fun shouldNotify(update: AppUpdate, now: Long): Boolean = automatic && shouldNotifyUpdate(
        key(update), key(update).takeIf { it in prefs.getStringSet("skipped_versions", emptySet()).orEmpty() }, prefs.getString("notified", null), prefs.getLong("notified_at", 0), now)

    fun notified(update: AppUpdate, now: Long) {
        prefs.edit().putString("notified", key(update)).putLong("notified_at", now).apply()
    }

    fun skip(key: String) {
        val skipped = prefs.getStringSet("skipped_versions", emptySet()).orEmpty() + key
        prefs.edit().putStringSet("skipped_versions", skipped).apply()
    }

    companion object { fun key(update: AppUpdate) = "${update.source.name}:${update.version}" }
}

internal object UpdateNotifications {
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!UpdatePreferences(context).automatic || AppUpdates(context).source() == null) {
            scheduler.cancel(UPDATE_JOB_ID)
            cancel(context)
        } else if (scheduler.getPendingJob(UPDATE_JOB_ID)?.initialBackoffMillis != UPDATE_RETRY_INTERVAL) {
            scheduler.schedule(JobInfo.Builder(UPDATE_JOB_ID, ComponentName(context, UpdateCheckService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(1))
                .setBackoffCriteria(UPDATE_RETRY_INTERVAL, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build())
        }
    }

    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(UPDATE_NOTIFICATION_ID)

    fun enabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(UPDATE_NOTIFICATION_CHANNEL)
            ?.importance != NotificationManager.IMPORTANCE_NONE

    fun show(context: Context, update: AppUpdate, now: Long = System.currentTimeMillis()) {
        val prefs = UpdatePreferences(context)
        // A user can change source or disable checks while a request is in flight.
        if (AppUpdates(context).source() != update.source || !prefs.shouldNotify(update, now)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(UPDATE_NOTIFICATION_CHANNEL,
            context.uiText(R.string.updates), NotificationManager.IMPORTANCE_DEFAULT))
        if (!enabled(context)) return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(context, UPDATE_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).setAction(OPEN_UPDATES_ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        fun action(action: String) = PendingIntent.getBroadcast(context, UPDATE_NOTIFICATION_ID,
            Intent(context, UpdateActionReceiver::class.java).setAction(action)
                .putExtra("version_key", UpdatePreferences.key(update)), flags)
        val source = context.uiText(if (update.source == UpdateSource.FDROID) R.string.update_fdroid else R.string.update_github)
        val notification = NotificationCompat.Builder(context, UPDATE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(context.uiText(R.string.update_available, update.version))
            .setContentText(context.uiText(R.string.update_notification_text, source))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.uiText(R.string.update_notification_text, source)))
            .setContentIntent(open).setAutoCancel(true)
            .addAction(0, context.uiText(R.string.update_notification_open), open)
            .addAction(0, context.uiText(R.string.update_skip), action(SKIP_UPDATE_ACTION))
            .addAction(0, context.uiText(R.string.update_disable), action(DISABLE_UPDATES_ACTION))
            .build()
        try {
            manager.notify(UPDATE_NOTIFICATION_ID, notification)
            prefs.notified(update, now)
        } catch (_: SecurityException) { /* Notification permission may be revoked during the check. */ }
    }
}

class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = UpdatePreferences(context)
        when (intent.action) {
            SKIP_UPDATE_ACTION -> {
                val key = intent.getStringExtra("version_key") ?: return
                prefs.skip(key)
            }
            DISABLE_UPDATES_ACTION -> prefs.automatic = false
            else -> return
        }
        UpdateNotifications.cancel(context)
        UpdateNotifications.schedule(context)
    }
}

class UpdateCheckService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var check: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val updates = AppUpdates(this)
        val source = updates.source() ?: return false
        if (!UpdatePreferences(this).automatic) return false
        check = scope.launch {
            val retry = runBackgroundUpdateCheck(this@UpdateCheckService, source, updates::check)
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        UpdatePreferences(this).backgroundFinished("interrupted")
        check?.cancel()
        return UpdatePreferences(this).automatic
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

internal suspend fun runBackgroundUpdateCheck(
    context: Context,
    source: UpdateSource,
    check: suspend (UpdateSource) -> AppUpdate?,
): Boolean {
    val prefs = UpdatePreferences(context)
    prefs.backgroundStarted(source)
    var retry = false
    try {
        val update = check(source)
        prefs.detected(source, update)
        prefs.backgroundFinished(if (update == null) "current" else "available")
        if (update != null) UpdateNotifications.show(context, update)
        else if (AppUpdates(context).source() == source) UpdateNotifications.cancel(context)
    } catch (cancelled: CancellationException) {
        prefs.backgroundFinished("interrupted")
        throw cancelled
    } catch (error: Exception) {
        retry = error is java.io.IOException
        prefs.backgroundFinished(if (retry) "network_error" else "error")
    }
    return retry && prefs.automatic && AppUpdates(context).source() == source
}
