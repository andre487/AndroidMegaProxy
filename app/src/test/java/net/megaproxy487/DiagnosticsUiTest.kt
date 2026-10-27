package net.megaproxy487

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import net.megaproxy487.vpn.TestDiagnosticLog
import net.megaproxy487.vpn.Http3ProbeResult
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsUiTest : MainUiTestBase() {
    @Test fun openingDiagnosticsStartsConnectionTest() {
        content { DiagnosticsScreen(activity, autoStart = true, onBack = {}) }
        val app = org.robolectric.Shadows.shadowOf(activity.application as android.app.Application)
        assertEquals("net.megaproxy487.TEST", app.nextStartedService?.action)
        assertNull(app.nextStartedService)
        node(R.string.run_again).assertIsNotEnabled()
        node(R.string.cloudflare_speed_run).assertDoesNotExist()
    }

    @Test fun restoredScreenDoesNotRerunCompletedConnectionTest() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent { androidx.compose.material3.MaterialTheme {
            DiagnosticsScreen(activity, autoStart = true, onBack = {})
        } }
        drainConfigIo()
        val app = org.robolectric.Shadows.shadowOf(activity.application as android.app.Application)
        assertEquals("net.megaproxy487.TEST", app.nextStartedService?.action)
        compose.runOnIdle { TestDiagnosticLog.succeed("203.0.113.7", "DE") }
        restoration.emulateSavedInstanceStateRestore()
        drainConfigIo()
        assertNull(app.nextStartedService)
        node(R.string.test_passed).assertExists()
        node(R.string.cloudflare_speed_run).assertExists()
    }

    @Test fun exportsSharedLogToPickedDocument() {
        TestDiagnosticLog.fail()
        TestDiagnosticLog.add("event=connection_marker")
        TestDiagnosticLog.add("event=speed_test route=tunnel result=success")
        val uri = android.net.Uri.parse("content://ui-test/diagnostics.log")
        val output = java.io.ByteArrayOutputStream()
        org.robolectric.Shadows.shadowOf(activity.contentResolver).registerOutputStream(uri, output)
        content { DiagnosticsScreen(activity, autoStart = false, onBack = {}) }
        node(R.string.export_action).performScrollTo().performClick()
        val request = org.robolectric.Shadows.shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, request.action)
        compose.runOnIdle {
            org.robolectric.Shadows.shadowOf(activity).receiveResult(request, android.app.Activity.RESULT_OK,
                android.content.Intent().setData(uri))
        }
        waitForText(text(R.string.diagnostic_log_exported))
        assertEquals(TestDiagnosticLog.entries.joinToString("\n"), output.toString("UTF-8"))
    }

    @Test fun http3ProvidersShowIndependentResultsAndResetOnAnotherRun() {
        TestDiagnosticLog.succeed("203.0.113.7", "DE", listOf(
            Http3ProbeResult("Cloudflare", true), Http3ProbeResult("BrowserLeaks", false),
        ))
        content { DiagnosticsScreen(activity, autoStart = false, onBack = {}) }
        compose.onNodeWithContentDescription(activity.uiText(
            R.string.test_http3_provider_a11y, "Cloudflare", activity.uiText(R.string.test_http3_confirmed))).assertIsDisplayed()
        compose.onNodeWithContentDescription(activity.uiText(
            R.string.test_http3_provider_a11y, "BrowserLeaks", activity.uiText(R.string.test_http3_unavailable))).assertIsDisplayed()
        compose.runOnIdle { TestDiagnosticLog.begin() }
        compose.runOnIdle { assertTrue(TestDiagnosticLog.http3.value.isEmpty()) }
        node(R.string.test_http3_help).assertDoesNotExist()
    }

    @Test fun runningDiagnosticCannotBeStartedAgainAndKeepsItsLog() {
        TestDiagnosticLog.begin()
        TestDiagnosticLog.add("event=test_marker")
        content { DiagnosticsScreen(activity, autoStart = true, onBack = {}) }
        node(R.string.run_again).assertIsNotEnabled()
        node(R.string.copy_log).performScrollTo().assertIsEnabled().performClick()
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertTrue(clipboard.primaryClip!!.getItemAt(0).text.contains("event=test_marker"))
    }

    @Test fun completedDiagnosticShowsExitIpAndAllowsAnotherRun() {
        TestDiagnosticLog.succeed("203.0.113.7", "DE")
        content { DiagnosticsScreen(activity, autoStart = false, onBack = {}) }
        node(R.string.test_passed).assertIsDisplayed()
        compose.onNodeWithText(activity.uiText(R.string.proxy_exit_ip, "203.0.113.7")).assertIsDisplayed()
        node(R.string.run_again).assertIsEnabled()
    }

    @Test fun failureShowsStatusAndEmptyLogCannotBeCopied() {
        TestDiagnosticLog.fail()
        content { DiagnosticsScreen(activity, autoStart = false, onBack = {}) }
        node(R.string.test_failed).assertIsDisplayed()
        node(R.string.run_again).assertIsEnabled()
        node(R.string.copy_log).assertIsNotEnabled()
    }

    @Test fun speedIsUnlockedOnlyAfterSuccessAndRelockedOnRetest() {
        content { DiagnosticsScreen(activity, autoStart = false, onBack = {}) }
        node(R.string.cloudflare_speed_run).assertDoesNotExist()
        node(R.string.diagnostics_speed_locked).assertExists()
        compose.runOnIdle { TestDiagnosticLog.fail() }
        node(R.string.cloudflare_speed_run).assertDoesNotExist()
        compose.runOnIdle { TestDiagnosticLog.succeed("203.0.113.7", "DE") }
        node(R.string.cloudflare_speed_run).performScrollTo().assertIsEnabled()
        compose.onNodeWithText("h2").performScrollTo().assertIsSelected()
        compose.onNodeWithText("h3").performScrollTo().performClick()
        compose.onNodeWithText(activity.uiText(R.string.cloudflare_speed_size, 25)).performScrollTo().performClick()
        compose.onNodeWithText(activity.uiText(R.string.cloudflare_speed_explanation, 25, 100)).assertExists()
        compose.runOnIdle { TestDiagnosticLog.begin() }
        node(R.string.cloudflare_speed_run).assertDoesNotExist()
        node(R.string.diagnostics_speed_locked).assertExists()
    }

    @Test fun speedPreservesConnectionLogAndBothCanBeCopied() {
        val connectivity = activity.getSystemService(android.net.ConnectivityManager::class.java)
        org.robolectric.Shadows.shadowOf(connectivity).clearAllNetworks()
        TestDiagnosticLog.succeed("203.0.113.7", "DE")
        TestDiagnosticLog.add("event=connection_marker")
        content { DiagnosticsScreen(activity, autoStart = false, onBack = {}) }
        node(R.string.cloudflare_speed_run).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            TestDiagnosticLog.entries.any { it.contains("event=speed_test route=tunnel result=unavailable") }
        }
        node(R.string.copy_log).performScrollTo().performClick()
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(text.contains("event=connection_marker"))
        assertTrue(text.contains("event=speed_test route=underlying"))
        assertTrue(text.contains("event=speed_test route=tunnel"))
        node(R.string.export_action).assertIsEnabled()
    }

    @Test fun clearingDiagnosticLogRequiresConfirmation() {
        content { DiagnosticLogScreen(activity, {}) }
        node(R.string.clear_action).performScrollTo().performClick()
        node(R.string.clear_diagnostic_log_title).assertIsDisplayed()
        node(R.string.cancel).performClick()
        node(R.string.clear_diagnostic_log_title).assertDoesNotExist()
    }
}
