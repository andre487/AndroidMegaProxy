package net.megaproxy487

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiSelector
import net.megaproxy487.data.ConfigTransfer
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
}
