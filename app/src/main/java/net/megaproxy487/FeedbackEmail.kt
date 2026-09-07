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
import java.util.UUID

object FeedbackEmail {
    private const val ADDRESS = "megaproxy-feedback@hotmail.com"

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
            appendLine(if (crashReport) context.uiText(R.string.crash_instructions) else context.uiText(R.string.feedback_instructions))
            appendLine()
            appendLine(context.uiText(R.string.feedback_diagnostics))
            appendLine(context.uiText(R.string.feedback_device, Build.MANUFACTURER, Build.MODEL))
            appendLine(context.uiText(R.string.feedback_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
            if (Build.VERSION.SECURITY_PATCH.isNotBlank()) appendLine(context.uiText(R.string.feedback_patch, Build.VERSION.SECURITY_PATCH))
            appendLine(context.uiText(R.string.feedback_app, versionName, versionCode))
            appendLine("ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            appendLine(context.uiText(R.string.feedback_connection, connection.name.lowercase()))
            appendLine(context.uiText(R.string.feedback_always_on, alwaysOn, lockdown))
            appendLine(context.uiText(R.string.feedback_routing, context.uiText(if (config.routeAllApps) R.string.feedback_routing_global else R.string.feedback_routing_split), config.selectedPackages.size))
            appendLine(context.uiText(R.string.feedback_https, config.profile.name))
            appendLine(context.uiText(R.string.feedback_ssh, config.sshProfile.name))
            appendLine(context.uiText(R.string.feedback_dns, config.dnsProvider.name))
            appendLine(context.uiText(R.string.feedback_ipv6, config.allowIpv6, config.bypassLocalNetworks))
            appendLine(context.uiText(R.string.feedback_certificate, !config.allowInvalidProxyCertificate))
            appendLine(context.uiText(R.string.feedback_logs))
        }

        val directory = File(context.cacheDir, "feedback").also { it.mkdirs() }
        directory.listFiles { file -> file.isFile && file.extension == "zip" }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(2)
            .forEach(File::delete)
        // Do not reuse a content URI that may still be granted to an email app:
        // a stale grant must never expose diagnostics generated in the future.
        val logFile = File(directory, "megaproxy-diagnostics-${UUID.randomUUID()}.zip")
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
            putExtra(Intent.EXTRA_SUBJECT, context.uiText(if (crashReport) R.string.feedback_crash_subject else R.string.feedback_subject))
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, logUri)
            clipData = ClipData.newUri(context.contentResolver, context.uiText(R.string.feedback_archive), logUri)
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
        if (targeted.isEmpty()) return Intent.createChooser(sendIntent, context.uiText(R.string.feedback_send))
        return Intent.createChooser(targeted.first(), context.uiText(R.string.feedback_send)).apply {
            if (targeted.size > 1) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, targeted.drop(1).toTypedArray())
            }
        }
    }
}
