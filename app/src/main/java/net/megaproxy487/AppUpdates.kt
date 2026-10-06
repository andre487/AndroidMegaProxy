package net.megaproxy487

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class UpdateSource { FDROID, GITHUB }

internal fun updateSourceForInstaller(installer: String?, handlesFdroidRepos: Boolean? = false): UpdateSource? = when {
    installer.isNullOrBlank() -> null
    installer == "org.fdroid.fdroid" || installer == "org.fdroid.fdroid.privileged" -> UpdateSource.FDROID
    handlesFdroidRepos == null -> null
    handlesFdroidRepos -> UpdateSource.FDROID
    else -> UpdateSource.GITHUB
}

internal data class AppUpdate(
    val source: UpdateSource,
    val version: String,
    val apkUrl: String? = null,
    val sha256: String? = null,
    val size: Long = 0,
)

internal const val FDROID_APP_URL = "https://f-droid.org/packages/net.megaproxy487/"
internal const val GITHUB_RELEASES_API = "https://api.github.com/repos/andre487/AndroidMegaProxy/releases/latest"
internal const val FDROID_UPDATE_API = "https://f-droid.org/api/v1/packages/net.megaproxy487"
private const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/andre487/AndroidMegaProxy/releases/download/"
internal const val MAX_UPDATE_APK_BYTES = 200L * 1024 * 1024

private fun versionParts(version: String): List<Long> {
    require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version))
    return version.split('.').map(String::toLong)
}

internal fun newerVersion(candidate: String, installed: String): Boolean {
    val comparison = versionParts(candidate).zip(versionParts(installed))
    return comparison.firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } ?: false
}

internal fun parseGithubUpdate(json: String, installedVersion: String, variant: String): AppUpdate? {
    require(variant in setOf("universal", "arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
    val filename = "mega-proxy-$variant.apk"
    val release = JSONObject(json)
    require(!release.getBoolean("draft") && !release.getBoolean("prerelease"))
    val tag = release.getString("tag_name")
    require(tag.startsWith("v"))
    val version = tag.removePrefix("v")
    if (!newerVersion(version, installedVersion)) return null
    val assets = release.getJSONArray("assets")
    val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
        .filter { it.getString("name") == filename }
    require(matches.size == 1)
    val asset = matches.single()
    val url = asset.getString("browser_download_url")
    require(url == "$RELEASE_DOWNLOAD_PREFIX$tag/$filename")
    require(asset.getString("state") == "uploaded")
    val digest = asset.getString("digest")
    require(Regex("sha256:[a-fA-F0-9]{64}").matches(digest))
    val size = asset.getLong("size")
    require(size in 1..MAX_UPDATE_APK_BYTES)
    return AppUpdate(UpdateSource.GITHUB, version, url, digest.removePrefix("sha256:").lowercase(), size)
}

internal fun parseFdroidUpdate(json: String, installedCode: Long): AppUpdate? {
    val result = JSONObject(json)
    require(result.getString("packageName") == "net.megaproxy487")
    val suggested = result.getLong("suggestedVersionCode")
    if (suggested <= installedCode) return null
    val packages = result.getJSONArray("packages")
    val candidate = (0 until packages.length()).map { packages.getJSONObject(it) }
        .firstOrNull { it.getLong("versionCode") == suggested }
    requireNotNull(candidate)
    return AppUpdate(UpdateSource.FDROID, candidate.getString("versionName"))
}

internal fun validateUpdateIdentity(
    packageName: String, version: String?, code: Long, signers: Set<String>,
    installedPackage: String, installedCode: Long, installedSigners: Set<String>, expectedVersion: String,
) {
    require(packageName == installedPackage && version == expectedVersion && code > installedCode)
    require(code % 1000 == installedCode % 1000)
    require(signers.isNotEmpty() && signers == installedSigners)
}

internal class AppUpdates(private val context: Context) {
    private val preferences = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val manager = context.packageManager

    @Suppress("DEPRECATION")
    fun source(): UpdateSource? {
        val saved = preferences.getString("source", null)
        UpdateSource.entries.firstOrNull { it.name == saved }?.let { return it }
        val installer = try {
            if (Build.VERSION.SDK_INT >= 30) manager.getInstallSourceInfo(context.packageName).installingPackageName
            else manager.getInstallerPackageName(context.packageName)
        } catch (_: Exception) { null }
        if (installer.isNullOrBlank()) return null
        val handlesRepos = try {
            listOf("fdroidrepo", "fdroidrepos").any { scheme ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://f-droid.org/repo"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(installer)
                manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    .any { it.activityInfo?.packageName == installer }
            }
        } catch (_: Exception) { null }
        return updateSourceForInstaller(installer, handlesRepos)
    }

    fun select(source: UpdateSource) {
        preferences.edit().putString("source", source.name).apply()
    }

    suspend fun check(source: UpdateSource): AppUpdate? = withContext(Dispatchers.IO) {
        val url = if (source == UpdateSource.FDROID) FDROID_UPDATE_API else GITHUB_RELEASES_API
        val json = request(url) { connection ->
            if (connection.responseCode == 404) throw UiException(R.string.update_not_published)
            if (connection.responseCode == 429 || connection.responseCode in 500..599)
                throw java.io.IOException("Update service temporarily unavailable")
            require(connection.responseCode == 200)
            connection.inputStream.use { stream ->
                String(readUpdateMetadata(stream), Charsets.UTF_8)
            }
        }
        if (source == UpdateSource.FDROID) parseFdroidUpdate(json, installedCode())
        else parseGithubUpdate(json, BuildConfig.VERSION_NAME, BuildConfig.APK_VARIANT)
    }

    suspend fun download(update: AppUpdate): File = withContext(Dispatchers.IO) {
        require(update.source == UpdateSource.GITHUB)
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File.createTempFile("update-", ".part", directory)
        val apk = File(directory, partial.nameWithoutExtension + ".apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            request(requireNotNull(update.apkUrl)) { connection ->
                require(connection.responseCode == 200)
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= update.size && total <= MAX_UPDATE_APK_BYTES)
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        require(total == update.size)
                    }
                }
            }
            require(digest.digest().hex() == update.sha256)
            verifyApk(partial, update.version)
            check(partial.renameTo(apk))
            apk
        } finally {
            partial.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun installedCode(): Long = PackageInfoCompat.getLongVersionCode(manager.getPackageInfo(context.packageName, 0))

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, version: String) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = requireNotNull(manager.getPackageArchiveInfo(file.absolutePath, flags))
        val installed = manager.getPackageInfo(context.packageName, flags)
        validateUpdateIdentity(archive.packageName, archive.versionName, PackageInfoCompat.getLongVersionCode(archive),
            signers(archive), context.packageName, PackageInfoCompat.getLongVersionCode(installed), signers(installed), version)
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

// GitHub release assets redirect to its CDN. Only HTTPS redirects on these exact hosts are allowed.
private fun <T> request(url: String, read: (HttpURLConnection) -> T): T {
    var current = url
    repeat(6) {
        val uri = URI(current)
        require(uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443))
        require(uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "f-droid.org"))
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "MegaProxy-Updates")
            if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                current = uri.resolve(requireNotNull(connection.getHeaderField("Location"))).toString()
            } else return read(connection)
        } finally { connection.disconnect() }
    }
    error("Too many redirects")
}

internal fun readUpdateMetadata(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size() + count <= 1024 * 1024)
        output.write(buffer, 0, count)
    }
}
