package net.megaproxy487

import android.app.Application
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.vpn.PersistentDiagnosticLog

class MegaProxyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val store = ConfigStore(this)
        PersistentDiagnosticLog.initialize(this, store.diagnosticLogLimitMb())
        CrashHandler.install(this)
    }
}
