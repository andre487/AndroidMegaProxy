package net.megaproxy487.vpn

import android.content.Context
import android.provider.Settings

data class AlwaysOnVpnStatus(
    val enabled: Boolean,
    val lockdown: Boolean,
    val configuredPackage: String?,
)

val AlwaysOnVpnStatus.hasOtherProvider: Boolean
    get() = configuredPackage != null && !enabled

fun readAlwaysOnVpnStatus(context: Context): AlwaysOnVpnStatus {
    val configuredPackage = runCatching {
        Settings.Secure.getString(context.contentResolver, "always_on_vpn_app")
            ?.takeIf(String::isNotBlank)
    }.getOrNull()
    val enabled = configuredPackage?.let { it == context.packageName }
        ?: ProxyVpnService.isAlwaysOnMode
    val lockdown = enabled && runCatching {
        Settings.Secure.getInt(context.contentResolver, "always_on_vpn_lockdown", 0) == 1
    }.getOrDefault(ProxyVpnService.isLockdownMode)
    return AlwaysOnVpnStatus(enabled, lockdown, configuredPackage)
}
