package net.megaproxy487

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiSelector
import net.megaproxy487.data.ConfigTransfer
import net.megaproxy487.data.ConfigSubscription
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentsDeviceTest : DeviceTestBase() {
    private fun screen() {
        scenario.onActivity { activity ->
            activity.setContent { MaterialTheme { ProfilesScreen(activity, {}, {}) } }
        }
        appNode(By.text("Device fixture"))
        ready()
    }
    private fun ready() {
        appNode(By.desc(text(R.string.export_action)).enabled(true))
        saved()
    }
    private fun systemNode(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2 {
        device.waitForIdle()
        val result = device.wait(Until.findObject(selector), 10_000)
        assertNotNull("Document picker control missing: $selector", result)
        return result
    }
    private fun downloads() {
        systemNode(By.desc("Show roots")).click()
        val downloads = device.findObject(UiSelector().resourceId("android:id/title").text("Downloads"))
        assertTrue("Downloads root missing", downloads.waitForExists(10_000))
        downloads.click()
    }
    private fun importFile() {
        appNode(By.desc(text(R.string.import_action)).enabled(true)).click()
        downloads()
    }
    @Test fun cancelledDocumentSelectionPreservesConfiguration() {
        screen()
        val before = ConfigTransfer.exportJson(store, true, true)
        appNode(By.desc(text(R.string.import_action)).enabled(true)).click()
        systemNode(By.desc("Show roots"))
        device.pressBack()
        device.waitForIdle()
        ready()
        assertEquals(before, ConfigTransfer.exportJson(store, true, true))
        appNode(By.desc(text(R.string.export_action)).enabled(true)).click()
        systemNode(By.text(text(R.string.export_action))).click()
        systemNode(By.desc("Show roots"))
        device.pressBack()
        device.waitForIdle()
        saved()
        assertEquals(before, ConfigTransfer.exportJson(store, true, true))
    }
    @Test fun systemProviderExportAndImportRoundTrip() {
        val filename = "megaproxy-device-${System.nanoTime()}.json"
        screen()
        try {
            appNode(By.desc(text(R.string.export_action)).enabled(true)).click()
            appNode(By.text(text(R.string.passwords_omitted_message)))
            systemNode(By.text(text(R.string.export_action))).click()
            downloads()
            // A fresh API 26 Gboard tutorial overlays the filename and hides it from accessibility.
            device.waitForIdle()
            if (device.hasObject(By.pkg("com.google.android.inputmethod.latin"))) {
                device.pressBack()
                device.waitForIdle()
            }
            systemNode(By.clazz("android.widget.EditText")).text = filename
            systemNode(By.res("android:id/button1")).click()
            appNode(By.text(text(R.string.configuration_exported)))
            systemNode(By.text(text(R.string.ok))).click()
            val exported = device.executeShellCommand("cat /sdcard/Download/$filename")
            val configuration = ConfigTransfer.importJson(exported)
            assertEquals("Device fixture", configuration.profiles.single().name)
            assertFalse(exported.contains(argument("proxyPassword")))
            io { store.saveProfile(store.activeProfile().copy(name = "Changed locally")) }
            importFile()
            systemNode(By.text(filename)).click()
            appNode(By.text(text(R.string.import_anyway)))
            systemNode(By.text(text(R.string.import_anyway))).click()
            await("Import did not restore the exported profile") { store.activeProfile().name == "Device fixture" }
            saved()
            assertEquals(configuration.profiles.single().id, store.activeProfileId())
            assertTrue("Omitted password must preserve the local secret", store.activeProfile().config.password == argument("proxyPassword"))
        } finally {
            device.executeShellCommand("rm -f /sdcard/Download/$filename")
        }
    }
    @Test fun subscriptionSnapshotsUseDeviceKeystoreAndBundledSchema() {
        val local = store.activeProfile()
        val first = local.copy(id = "subscription-one", name = "Subscribed")
        val second = first.copy(id = "subscription-two", name = "Replacement")
        val settings = ConfigSubscription("https://feed.example/config?token=device-secret-token",
            listOf("https://backup.example/config"), "reader", "device-subscription-secret", enabled = false)
        fun document(profile: net.megaproxy487.model.ProxyProfile) = JSONObject()
            .put("schema", ConfigTransfer.SCHEMA_ID).put("version", 8)
            .put("activeProfileId", profile.id)
            .put("profiles", JSONArray().put(ConfigTransfer.encodeProfile(profile, true, false)))
        io { store.importConfiguration(ConfigTransfer.importJson(document(first)
            .put("subscription", settings.toJson()).toString())) }
        val snapshot = document(second).toString()
        val sources = mutableListOf<String>()
        val applied = runBlocking { ConfigSubscriptions.refreshNow(context, true, { _, url ->
            sources += url
            if (url == settings.url) "<html>invalid snapshot</html>" else snapshot
        }, { false }) }
        assertTrue(applied)
        assertEquals(listOf(settings.url) + settings.fallbackUrls, sources)
        assertNull(store.profile(first.id))
        assertNotNull(store.profile(second.id))
        assertNotNull(store.profile(local.id))
        assertFalse(store.isConnectionDesired())
        val reopened = net.megaproxy487.data.ConfigStore(context).subscriptionState()!!
        assertEquals(settings, reopened.settings)
        assertEquals(setOf(second.id), reopened.ownedIds)
        assertEquals(1, reopened.sourceIndex)
        val raw = context.getSharedPreferences("proxy_config", android.content.Context.MODE_PRIVATE).all.toString()
        assertFalse(raw.contains("device-secret-token"))
        assertFalse(raw.contains("device-subscription-secret"))
        val before = ConfigTransfer.exportJson(store, true)
        assertFalse(runBlocking { ConfigSubscriptions.refreshNow(context, true,
            { _, _ -> JSONObject(snapshot).apply { getJSONArray("profiles").getJSONObject(0).getJSONObject("proxy").put("port", "bad") }.toString() }, { false }) })
        assertEquals(before, ConfigTransfer.exportJson(store, true))
        assertTrue(store.subscriptionState()!!.failed)
        io { store.saveSubscription(null) }
        assertNotNull(store.profile(second.id))
        assertNotNull(store.profile(local.id))
    }

}
