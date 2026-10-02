package net.megaproxy487

import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun installerSelectionDoesNotGuessWhenUnknown() {
        assertNull(updateSourceForInstaller(null))
        assertNull(updateSourceForInstaller(""))
        assertEquals(UpdateSource.FDROID, updateSourceForInstaller("org.fdroid.fdroid"))
        assertEquals(UpdateSource.FDROID, updateSourceForInstaller("org.fdroid.fdroid.privileged"))
        for (installer in listOf("com.android.chrome", "com.android.documentsui", "org.example.other", "net.megaproxy487")) {
            assertEquals(UpdateSource.GITHUB, updateSourceForInstaller(installer))
        }
    }

    private fun github(tag: String = "v0.1.2"): JSONObject = JSONObject().apply {
        put("tag_name", tag); put("draft", false); put("prerelease", false)
        put("assets", JSONArray().put(JSONObject().apply {
            put("name", "mega-proxy-universal.apk"); put("state", "uploaded"); put("size", 123)
            put("digest", "sha256:" + "ab".repeat(32))
            put("browser_download_url", "https://github.com/andre487/AndroidMegaProxy/releases/download/$tag/mega-proxy-universal.apk")
        }))
    }

    @Test fun githubUsesStableUniversalAssetAndComparesVersionsNumerically() {
        assertNull(parseGithubUpdate(github().toString(), "0.1.2", "universal"))
        assertNull(parseGithubUpdate(github().toString(), "0.2.0", "universal"))
        assertTrue(newerVersion("0.10.0", "0.9.9"))
        val update = parseGithubUpdate(github().toString(), "0.1.1", "universal")!!
        assertEquals("0.1.2", update.version)
        assertEquals(UpdateSource.GITHUB, update.source)
        assertEquals(123L, update.size)
        assertEquals("ab".repeat(32), update.sha256)
    }

    @Test fun githubRejectsUnsafeOrIncompleteAssetsAndPrereleases() {
        fun reject(change: (JSONObject) -> Unit) {
            val release = github().also(change)
            assertThrows(IllegalArgumentException::class.java) { parseGithubUpdate(release.toString(), "0.1.1", "universal") }
        }
        reject { it.put("prerelease", true) }
        reject { it.put("draft", true) }
        reject { it.put("tag_name", "v0.1.2-beta") }
        reject { it.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.example/update.apk") }
        reject { it.getJSONArray("assets").getJSONObject(0).put("size", MAX_UPDATE_APK_BYTES + 1) }
        reject { it.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:bad") }
        reject { it.put("assets", JSONArray()) }
        reject { val assets = it.getJSONArray("assets"); assets.put(assets.getJSONObject(0)) }
    }

    @Test fun fdroidUsesSuggestedPublishedVersionNotNewestUnstableVersion() {
        val json = """{"packageName":"net.megaproxy487","suggestedVersionCode":15000,"packages":[
            {"versionName":"0.2.0-beta","versionCode":16000},{"versionName":"0.1.2","versionCode":15000}]}"""
        assertEquals("0.1.2", parseFdroidUpdate(json, 14004)!!.version)
        assertNull(parseFdroidUpdate(json, 15000))
        assertNull(parseFdroidUpdate(json, 15004))
        assertThrows(IllegalArgumentException::class.java) { parseFdroidUpdate(json.replace("net.megaproxy487", "other"), 14000) }
        assertThrows(IllegalArgumentException::class.java) { parseFdroidUpdate(json.replace("\"suggestedVersionCode\":15000", "\"suggestedVersionCode\":17000"), 14000) }
    }

    @Test fun apkIdentityRejectsWrongPackageSignerVersionAndDowngrade() {
        fun verify(pkg: String = "net.megaproxy487", version: String = "0.1.2", code: Long = 15004,
                   signatures: Set<String> = setOf("release")) = validateUpdateIdentity(
            pkg, version, code, signatures, "net.megaproxy487", 14004, setOf("release"), "0.1.2")
        verify()
        assertThrows(IllegalArgumentException::class.java) { verify(code = 15000) }
        assertThrows(IllegalArgumentException::class.java) { verify(pkg = "other") }
        assertThrows(IllegalArgumentException::class.java) { verify(version = "0.1.1") }
        assertThrows(IllegalArgumentException::class.java) { verify(code = 14000) }
        assertThrows(IllegalArgumentException::class.java) { verify(code = 14004) }
        assertThrows(IllegalArgumentException::class.java) { verify(signatures = setOf("debug")) }
        assertThrows(IllegalArgumentException::class.java) { verify(signatures = emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { verify(signatures = setOf("release", "other")) }
    }

    @Test fun githubPreservesEveryApkVariantAndRejectsMissingVariant() {
        for (variant in listOf("universal", "arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
            val release = github().toString().replace("mega-proxy-universal.apk", "mega-proxy-$variant.apk")
            assertTrue(parseGithubUpdate(release, "0.1.1", variant)!!.apkUrl!!.endsWith("mega-proxy-$variant.apk"))
        }
        assertThrows(IllegalArgumentException::class.java) { parseGithubUpdate(github().toString(), "0.1.1", "arm64-v8a") }
        assertThrows(IllegalArgumentException::class.java) { parseGithubUpdate(github().toString(), "0.1.1", "unknown") }
    }

    @Test fun ignoredUpdateWaitsAWeekAndSkippedVersionStaysSkipped() {
        val time = 1_000_000L
        assertTrue(shouldNotifyUpdate("GITHUB:2", null, null, 0, time))
        assertFalse(shouldNotifyUpdate("GITHUB:2", null, "GITHUB:2", time, time + UPDATE_REMINDER_INTERVAL - 1))
        assertTrue(shouldNotifyUpdate("GITHUB:2", null, "GITHUB:2", time, time + UPDATE_REMINDER_INTERVAL))
        assertFalse(shouldNotifyUpdate("GITHUB:2", "GITHUB:2", "GITHUB:2", time, time + UPDATE_REMINDER_INTERVAL * 2))
        assertTrue(shouldNotifyUpdate("GITHUB:3", "GITHUB:2", "GITHUB:2", time, time + 1))
        assertTrue(shouldNotifyUpdate("FDROID:2", "GITHUB:2", "GITHUB:2", time, time + 1))
        assertFalse(shouldNotifyUpdate("GITHUB:2", null, "GITHUB:2", time, time - 1))
    }

    @Test fun oversizedMetadataIsRejectedBeforeParsing() {
        assertArrayEquals(byteArrayOf(1, 2), readUpdateMetadata(ByteArrayInputStream(byteArrayOf(1, 2))))
        assertThrows(IllegalArgumentException::class.java) { readUpdateMetadata(ByteArrayInputStream(ByteArray(1024 * 1024 + 1))) }
    }
}
