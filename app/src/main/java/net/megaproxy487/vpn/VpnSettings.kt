package net.megaproxy487.vpn

import android.app.Activity
import android.content.Intent
import android.provider.Settings

fun openAndroidVpnSettings(activity: Activity) {
    val vpnSettings = Intent(Settings.ACTION_VPN_SETTINGS)
    runCatching { activity.startActivity(vpnSettings) }
        .recoverCatching { activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
}
