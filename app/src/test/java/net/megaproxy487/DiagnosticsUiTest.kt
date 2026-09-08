package net.megaproxy487

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.*
import net.megaproxy487.vpn.TestDiagnosticLog
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsUiTest : MainUiTestBase() {
    @Test fun runningDiagnosticCannotBeStartedAgainAndKeepsItsLog() {
        TestDiagnosticLog.begin()
        TestDiagnosticLog.add("event=test_marker")
        content { ConnectionTestScreen(activity, autoStart = true, onBack = {}) }
        node(R.string.run_again).assertIsNotEnabled()
        node(R.string.copy_log).assertIsEnabled().performClick()
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertTrue(clipboard.primaryClip!!.getItemAt(0).text.contains("event=test_marker"))
    }

    @Test fun completedDiagnosticShowsExitIpAndAllowsAnotherRun() {
        TestDiagnosticLog.succeed("203.0.113.7", "DE")
        content { ConnectionTestScreen(activity, autoStart = false, onBack = {}) }
        node(R.string.test_passed).assertIsDisplayed()
        compose.onNodeWithText(activity.uiText(R.string.proxy_exit_ip, "203.0.113.7")).assertIsDisplayed()
        node(R.string.run_again).assertIsEnabled()
    }

    @Test fun failureShowsStatusAndEmptyLogCannotBeCopied() {
        TestDiagnosticLog.fail()
        content { ConnectionTestScreen(activity, autoStart = false, onBack = {}) }
        node(R.string.test_failed).assertIsDisplayed()
        node(R.string.run_again).assertIsEnabled()
        node(R.string.copy_log).assertIsNotEnabled()
    }

    @Test fun clearingDiagnosticLogRequiresConfirmation() {
        content { DiagnosticLogScreen(activity, {}) }
        node(R.string.clear_action).performScrollTo().performClick()
        node(R.string.clear_diagnostic_log_title).assertIsDisplayed()
        node(R.string.cancel).performClick()
        node(R.string.clear_diagnostic_log_title).assertDoesNotExist()
    }
}
