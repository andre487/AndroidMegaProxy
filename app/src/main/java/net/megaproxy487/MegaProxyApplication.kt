package net.megaproxy487

import android.app.Application
import android.content.Context
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigIoDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.megaproxy487.vpn.PersistentDiagnosticLog

class MegaProxyApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        CoroutineScope(SupervisorJob() + ConfigIoDispatcher).launch {
            val store = ConfigStore(this@MegaProxyApplication)
            PersistentDiagnosticLog.initialize(this@MegaProxyApplication, store.diagnosticLogLimitMb())
            UpdateNotifications.schedule(this@MegaProxyApplication)
            ConfigSubscriptions.schedule(this@MegaProxyApplication)
        }
    }
}
