package net.megaproxy487.vpn

import android.content.Context
import android.provider.Settings

data class AlwaysOnVpnStatus(
    val enabled: Boolean,
    val lockdown: Boolean,
)

fun readAlwaysOnVpnStatus(context: Context): AlwaysOnVpnStatus {
    val enabled = runCatching {
        Settings.Secure.getString(context.contentResolver, "always_on_vpn_app") == context.packageName
    }.getOrDefault(ProxyVpnService.isAlwaysOnMode)
    val lockdown = enabled && runCatching {
        Settings.Secure.getInt(context.contentResolver, "always_on_vpn_lockdown", 0) == 1
    }.getOrDefault(ProxyVpnService.isLockdownMode)
    return AlwaysOnVpnStatus(enabled, lockdown)
}
