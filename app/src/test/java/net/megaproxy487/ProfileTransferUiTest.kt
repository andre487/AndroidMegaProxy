package net.megaproxy487

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProfileTransferUiTest : MainUiTestBase() {
    private fun screen() {
        content { ProfilesScreen(activity, {}, {}) }
        waitForText("Primary")
        compose.waitUntil(10_000) { !icon(R.string.import_action).fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) }
    }
    private fun documentResult(uri: Uri) {
        var request: Intent? = null
        compose.waitUntil(10_000) { request = shadowOf(activity).nextStartedActivityForResult?.intent; request != null }
        compose.runOnIdle { shadowOf(activity).receiveResult(request!!, Activity.RESULT_OK, Intent().setData(uri)) }
    }

    @Test fun cloneCreatesIndependentProfile() {
        screen()
        node(R.string.clone).performClick()
        compose.waitUntil(10_000) { store.profiles().size == 2 }
        val profiles = store.profiles()
        assertNotEquals(profiles[0].id, profiles[1].id)
        assertEquals(profiles[0].config, profiles[1].config)
    }

    @Test fun deleteCanBeCancelledAndCannotRemoveLastProfile() {
        screen()
        node(R.string.delete).performClick()
        node(R.string.cancel).performClick()
        assertEquals(1, store.profiles().size)
        node(R.string.delete).performClick()
        compose.onNode(hasText(text(R.string.delete)) and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        assertEquals(1, store.profiles().size)
    }

    @Test fun deletingActiveProfileSelectsRemainingProfile() {
        val original = store.activeProfileId()
        val remaining = store.cloneProfile(original)!!
        screen()
        compose.onAllNodesWithText(text(R.string.delete))[0].performClick()
        compose.onNode(hasText(text(R.string.delete)) and hasAnyAncestor(isDialog()))
            .assertIsEnabled().performClick()
        compose.waitUntil(10_000) { store.profiles().size == 1 }
        assertNull(store.profile(original))
        assertEquals(remaining.id, store.activeProfileId())
        waitForText(remaining.name)
    }

    @Test fun importReadsPickedFileAndAddsProfile() {
        val uri = Uri.parse("content://ui-test/proxies.txt")
        shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream("https://test:secret@import.example:443?title=Imported".toByteArray()))
        screen()
        icon(R.string.import_action).performClick()
        documentResult(uri)
        compose.waitUntil(10_000) { store.profiles().size == 2 }
        assertEquals("import.example", store.profiles().single { it.config.host == "import.example" }.config.host)
    }

    @Test fun malformedImportShowsErrorWithoutChangingProfiles() {
        val uri = Uri.parse("content://ui-test/broken.json")
        shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream("{broken".toByteArray()))
        screen()
        icon(R.string.import_action).performClick()
        documentResult(uri)
        waitForText(text(R.string.configuration_transfer))
        node(R.string.ok).performClick()
        assertEquals(1, store.profiles().size)
    }

    @Test fun jsonImportReportsIgnoredFieldsInItsExistingResultAndDiscardsThem() {
        val uri = Uri.parse("content://ui-test/portable.json")
        val root = org.json.JSONObject(net.megaproxy487.data.ConfigTransfer.exportJson(store, true))
            .put("browser", org.json.JSONObject().put("theme", "dark"))
            .put("future", "unknown-secret")
        shadowOf(activity.contentResolver).registerInputStream(uri, ByteArrayInputStream(root.toString().toByteArray()))
        screen()
        icon(R.string.import_action).performClick()
        documentResult(uri)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(text(R.string.config_import_browser_fields), substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        saved()
        val summary = compose.onNode(hasText(text(R.string.config_import_browser_fields), substring = true))
        summary.assertTextContains(text(R.string.config_import_unknown_fields), substring = true)
        assertFalse(summary.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString().contains("unknown-secret"))
        node(R.string.ok).performClick()
        assertEquals(1, store.profiles().size)
        val exported = net.megaproxy487.data.ConfigTransfer.exportJson(store, true)
        assertFalse(exported.contains("browser"))
        assertFalse(exported.contains("future"))
        assertFalse(exported.contains("unknown-secret"))
    }

    @Test fun defaultJsonExportOmitsSecrets() {
        val uri = Uri.parse("content://ui-test/export.json")
        val output = ByteArrayOutputStream()
        shadowOf(activity.contentResolver).registerOutputStream(uri, output)
        screen()
        icon(R.string.export_action).performClick()
        node(R.string.passwords_omitted_message).assertIsDisplayed()
        node(R.string.export_action).performClick()
        documentResult(uri)
        waitForText(text(R.string.configuration_exported))
        val exported = output.toString("UTF-8")
        assertTrue(exported.contains("proxy.example"))
        assertFalse(exported.contains("test-password"))
    }
}
