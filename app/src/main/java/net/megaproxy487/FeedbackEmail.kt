package net.megaproxy487

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.vpn.DiagnosticLog
import net.megaproxy487.vpn.PersistentDiagnosticLog
import net.megaproxy487.vpn.VpnConnectionState
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object FeedbackEmail {
    private const val ADDRESS = "megaproxy-feedback@hotmail.com"
    private const val SUBJECT = "[MegaProxy] Feedback"

    fun createIntent(
        context: Context,
        connection: VpnConnectionState,
        alwaysOn: Boolean,
        lockdown: Boolean,
        crashReport: Boolean = false,
    ): Intent {
        val store = ConfigStore(context)
        val config = store.globalConnectionSettings().applyTo(store.connectionProfile().config)
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val versionName = packageInfo.versionName ?: "unknown"
        val body = buildString {
            appendLine(if (crashReport) "Please describe what happened before the crash above this line." else "Please write your comments above this line.")
            appendLine()
            appendLine("--- Diagnostic information ---")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            if (Build.VERSION.SECURITY_PATCH.isNotBlank()) appendLine("Security patch: ${Build.VERSION.SECURITY_PATCH}")
            appendLine("App: $versionName ($versionCode)")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            appendLine("Connection: ${connection.name.lowercase()}")
            appendLine("Always-on: $alwaysOn; lockdown: $lockdown")
            appendLine("Routing: ${if (config.routeAllApps) "global" else "split"}; selected app count: ${config.selectedPackages.size}")
            appendLine("HTTPS fingerprint: ${config.profile.name}")
            appendLine("SSH fingerprint: ${config.sshProfile.name}")
            appendLine("DoH provider: ${config.dnsProvider.name}")
            appendLine("IPv6: ${config.allowIpv6}; bypass local networks: ${config.bypassLocalNetworks}")
            appendLine("Proxy certificate verification: ${!config.allowInvalidProxyCertificate}")
            appendLine("Privacy-filtered logs are attached.")
        }

        val directory = File(context.cacheDir, "feedback").also { it.mkdirs() }
        val logFile = File(directory, "megaproxy-diagnostics.zip")
        DiagnosticLog.add("event=feedback result=prepared")
        ZipOutputStream(logFile.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("megaproxy-diagnostic.log"))
            PersistentDiagnosticLog.copyTo(zip)
            zip.closeEntry()
        }
        val logUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logFile)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(ADDRESS))
            putExtra(Intent.EXTRA_SUBJECT, if (crashReport) "[MegaProxy] Crash" else SUBJECT)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, logUri)
            clipData = ClipData.newUri(context.contentResolver, "MegaProxy diagnostic log archive", logUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val emailPackages = context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$ADDRESS")), 0)
            .map { it.activityInfo.packageName }
            .distinct()
        val targeted = emailPackages.mapNotNull { packageName ->
            Intent(sendIntent).setPackage(packageName).takeIf {
                it.resolveActivity(context.packageManager) != null
            }
        }
        if (targeted.isEmpty()) return Intent.createChooser(sendIntent, "Send feedback")
        return Intent.createChooser(targeted.first(), "Send feedback").apply {
            if (targeted.size > 1) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, targeted.drop(1).toTypedArray())
            }
        }
    }
}
