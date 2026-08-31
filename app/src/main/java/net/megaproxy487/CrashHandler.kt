package net.megaproxy487

import android.content.Context
import android.os.Process
import net.megaproxy487.vpn.PersistentDiagnosticLog
import kotlin.system.exitProcess

object CrashHandler {
    private const val PREFS = "crash_state"
    private const val PENDING = "pending"
    @Volatile private var context: Context? = null

    fun install(context: Context) {
        this.context = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                PersistentDiagnosticLog.writeCrash(thread, throwable)
                this.context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    ?.edit()?.putBoolean(PENDING, true)?.commit()
            }
            if (previous != null) previous.uncaughtException(thread, throwable) else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun hasPendingReport(): Boolean =
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(PENDING, false) == true

    fun markReportHandled() {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(PENDING, false)?.apply()
    }
}
