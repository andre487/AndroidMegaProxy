package net.megaproxy487.vpn

import android.app.Activity
import android.content.Intent
import android.provider.Settings

const val OTHER_ALWAYS_ON_VPN_MESSAGE =
    "Another app is configured as Always-on VPN. Change the Always-on VPN app in Android settings, then try again."

fun openAndroidVpnSettings(activity: Activity) {
    val vpnSettings = Intent(Settings.ACTION_VPN_SETTINGS)
    runCatching { activity.startActivity(vpnSettings) }
        .recoverCatching { activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
}
