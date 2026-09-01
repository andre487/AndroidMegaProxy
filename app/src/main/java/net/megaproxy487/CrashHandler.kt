package net.megaproxy487

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import net.megaproxy487.vpn.PersistentDiagnosticLog
import kotlin.system.exitProcess

object CrashHandler {
    private const val PREFS = "crash_state"
    private const val PENDING = "pending"
    private lateinit var preferences: SharedPreferences

    fun install(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                PersistentDiagnosticLog.writeCrash(thread, throwable)
                preferences.edit().putBoolean(PENDING, true).commit()
            }
            if (previous != null) previous.uncaughtException(thread, throwable) else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun hasPendingReport(): Boolean =
        ::preferences.isInitialized && preferences.getBoolean(PENDING, false)

    fun markReportHandled() {
        if (::preferences.isInitialized) preferences.edit().putBoolean(PENDING, false).apply()
    }
}
