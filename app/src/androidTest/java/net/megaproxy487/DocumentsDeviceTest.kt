package net.megaproxy487

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import net.megaproxy487.data.ConfigTransfer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentsDeviceTest : DeviceTestBase() {
    private fun screen() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { MaterialTheme { ProfilesScreen(activity, {}, {}) } }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Device fixture").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun systemNode(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2 {
        val result = device.wait(Until.findObject(selector), 10_000)
        assertNotNull("Document picker control missing: $selector", result)
        return result
    }
    private fun downloads() {
        systemNode(By.desc("Show roots")).click()
        systemNode(By.text("Downloads")).click()
    }
    private fun importFile() {
        compose.onNodeWithContentDescription(text(R.string.import_action)).performClick()
        downloads()
    }
    @Test fun cancelledDocumentSelectionPreservesConfiguration() {
        screen()
        val before = ConfigTransfer.exportJson(store, true, true)
        compose.onNodeWithContentDescription(text(R.string.import_action)).performClick()
        systemNode(By.desc("Show roots"))
        device.pressBack()
        compose.waitForIdle()
        saved()
        assertEquals(before, ConfigTransfer.exportJson(store, true, true))
        compose.onNodeWithContentDescription(text(R.string.export_action)).performClick()
        node(R.string.export_action).performClick()
        systemNode(By.desc("Show roots"))
        device.pressBack()
        compose.waitForIdle()
        saved()
        assertEquals(before, ConfigTransfer.exportJson(store, true, true))
    }
    @Test fun systemProviderExportAndImportRoundTrip() {
        val filename = "megaproxy-device-${System.nanoTime()}.json"
        screen()
        try {
            compose.onNodeWithContentDescription(text(R.string.export_action)).performClick()
            node(R.string.passwords_omitted_message).assertExists()
            node(R.string.export_action).performClick()
            downloads()
            systemNode(By.clazz("android.widget.EditText")).text = filename
            systemNode(By.text("SAVE")).click()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.configuration_exported)).fetchSemanticsNodes().isNotEmpty() }
            node(R.string.ok).performClick()
            val exported = device.executeShellCommand("cat /sdcard/Download/$filename")
            val configuration = ConfigTransfer.importJson(exported)
            assertEquals("Device fixture", configuration.profiles.single().name)
            assertFalse(exported.contains(argument("proxyPassword")))
            io { store.saveProfile(store.activeProfile().copy(name = "Changed locally")) }
            importFile()
            systemNode(By.text(filename)).click()
            await("Import did not restore the exported profile") { store.activeProfile().name == "Device fixture" }
            saved()
            assertEquals(configuration.profiles.single().id, store.activeProfileId())
            assertTrue("Omitted password must preserve the local secret", store.activeProfile().config.password == argument("proxyPassword"))
        } finally {
            device.executeShellCommand("rm -f /sdcard/Download/$filename")
        }
    }
}
