package net.megaproxy487

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.megaproxy487.data.ConfigIoDispatcher
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.vpn.DiagnosticLog
import net.megaproxy487.vpn.ProxyVpnService

/** Restores a user-requested VPN connection after this package is replaced. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + ConfigIoDispatcher).launch {
            try {
                if (!ConfigStore(app).isConnectionDesired()) {
                    DiagnosticLog.add("event=package_replaced reconnect=skipped reason=connection_not_desired")
                    return@launch
                }
                if (VpnService.prepare(app) != null) {
                    // Android may revoke VPN consent independently of an app update. A
                    // background receiver cannot display the confirmation dialog.
                    DiagnosticLog.add("event=package_replaced reconnect=blocked reason=vpn_permission_required")
                    return@launch
                }
                DiagnosticLog.add("event=package_replaced reconnect=requested")
                ProxyVpnService.restoreAfterPackageUpdate(app)
            } catch (error: Exception) {
                DiagnosticLog.add("event=package_replaced reconnect=failed reason=${error.javaClass.simpleName}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
