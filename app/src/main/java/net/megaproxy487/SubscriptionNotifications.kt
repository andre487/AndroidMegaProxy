package net.megaproxy487

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.vpn.ProxyVpnService

internal const val SUBSCRIPTION_NOTIFICATION_ID = 48703
private const val CHANNEL = "configuration_changes"
internal const val EXTRA_SUBSCRIPTION_RECONNECT = "subscription_reconnect_token"

internal fun canReconnectSubscription(token: String?, current: String?, running: Boolean, desired: Boolean): Boolean =
    token != null && token == current && running && desired

internal object SubscriptionNotifications {
    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(SUBSCRIPTION_NOTIFICATION_ID)

    fun show(context: Context, token: String, running: () -> Boolean = { ProxyVpnService.isRunning }) {
        val store = ConfigStore(context)
        if (!canReconnectSubscription(token, store.pendingReconnectToken(), running(), store.isConnectionDesired())) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            context.uiText(R.string.subscription_notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(context, SUBSCRIPTION_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
        val reconnect = PendingIntent.getBroadcast(context, SUBSCRIPTION_NOTIFICATION_ID,
            Intent(context, SubscriptionReconnectReceiver::class.java).putExtra(EXTRA_SUBSCRIPTION_RECONNECT, token), flags)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(context.uiText(R.string.subscription_notification_title))
            .setContentText(context.uiText(R.string.subscription_notification_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.uiText(R.string.subscription_notification_text)))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, context.uiText(R.string.reconnect), reconnect).build()
        try {
            manager.notify(SUBSCRIPTION_NOTIFICATION_ID, notification)
            if (!canReconnectSubscription(token, store.pendingReconnectToken(), running(), store.isConnectionDesired())) cancel(context)
        }
        catch (_: SecurityException) { /* The in-app reconnect indicator remains available. */ }
    }
}

class SubscriptionReconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_SUBSCRIPTION_RECONNECT) ?: return
        ProxyVpnService.reconnectSubscription(context, token)
    }
}
