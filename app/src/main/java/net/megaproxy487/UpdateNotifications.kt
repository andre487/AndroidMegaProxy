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
internal const val UPDATE_JOB_ID = 48702
internal const val UPDATE_NOTIFICATION_ID = 48702
internal const val UPDATE_NOTIFICATION_CHANNEL = "app_updates"
internal const val OPEN_UPDATES_ACTION = "net.megaproxy487.OPEN_UPDATES"
internal const val SKIP_UPDATE_ACTION = "net.megaproxy487.SKIP_UPDATE"
internal const val DISABLE_UPDATES_ACTION = "net.megaproxy487.DISABLE_UPDATES"

internal fun shouldNotifyUpdate(key: String, skipped: String?, lastKey: String?, lastTime: Long, now: Long): Boolean =
    key != skipped && (key != lastKey || now - lastTime >= UPDATE_REMINDER_INTERVAL)

internal class UpdatePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply() }

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
        } else if (scheduler.getPendingJob(UPDATE_JOB_ID) == null) {
            scheduler.schedule(JobInfo.Builder(UPDATE_JOB_ID, ComponentName(context, UpdateCheckService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.DAYS.toMillis(1), TimeUnit.HOURS.toMillis(1))
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
            try {
                val update = updates.check(source)
                if (update != null) UpdateNotifications.show(this@UpdateCheckService, update)
                else UpdateNotifications.cancel(this@UpdateCheckService)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retry at the next scheduled check; never switch source on failure. */ }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        check?.cancel()
        return UpdatePreferences(this).automatic
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
