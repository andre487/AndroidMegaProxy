package net.megaproxy487

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationReminder {
    private const val PREFS_NAME = "battery_optimization_reminder"
    private const val LAST_REQUEST_AT = "last_request_at"
    internal const val REQUEST_INTERVAL_MS = 24 * 60 * 60 * 1000L

    fun maybeRequest(activity: Activity, now: Long = System.currentTimeMillis()) {
        val powerManager = activity.getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(activity.packageName)) return

        val preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastRequestAt = preferences.getLong(LAST_REQUEST_AT, 0L)
        if (!isRequestDue(now, lastRequestAt)) return

        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${activity.packageName}")
        }
        if (runCatching { activity.startActivity(request) }.isSuccess) {
            preferences.edit().putLong(LAST_REQUEST_AT, now).apply()
        }
    }

    internal fun isRequestDue(now: Long, lastRequestAt: Long): Boolean =
        lastRequestAt == 0L || now < lastRequestAt || now - lastRequestAt >= REQUEST_INTERVAL_MS
}
