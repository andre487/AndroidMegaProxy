package net.megaproxy487

import androidx.compose.ui.test.*
import net.megaproxy487.vpn.*
import org.junit.Test

class NavigationUiTest : MainUiTestBase() {
    @Test fun connectionTestMenuOpensDiagnosticAndBackReturnsHome() {
        TestDiagnosticLog.begin()
        content { MegaProxyNavHost(activity, readConnectionStats = { null }) }
        icon(R.string.main_actions).performClick()
        node(R.string.test_connection).performClick()
        compose.onNodeWithTag("screen-connection-test").assertIsDisplayed()
        node(R.string.run_again).assertIsNotEnabled()
        icon(R.string.back).performClick()
        compose.onNodeWithTag("screen-main").assertIsDisplayed()
    }

    @Test fun settingsDestinationsOpenAndBackReturnsToSettings() {
        content { MegaProxyNavHost(activity, readConnectionStats = { null }) }
        icon(R.string.main_actions).performClick()
        node(R.string.settings).performClick()
        compose.onNodeWithTag("screen-settings").assertIsDisplayed()
        for (destination in settingsDestinations) {
            node(destination.titleRes).performScrollTo().performClick()
            compose.onNodeWithTag("screen-${destination.route}").assertIsDisplayed()
            icon(R.string.back).performClick()
            compose.onNodeWithTag("screen-settings").assertIsDisplayed()
        }
        icon(R.string.back).performClick()
        compose.onNodeWithTag("screen-main").assertIsDisplayed()
    }

    @Test fun addProfileAndBackReturnToListWithoutCreatingEmptyDraft() {
        content { MegaProxyNavHost(activity, readConnectionStats = { null }) }
        icon(R.string.main_actions).performClick()
        node(R.string.settings).performClick()
        node(R.string.profiles).performClick()
        node(R.string.add_profile).performClick()
        compose.onNodeWithTag("screen-profile-editor").assertIsDisplayed()
        icon(R.string.back).performClick()
        compose.onNodeWithTag("screen-profiles").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, store.profiles().size) }
    }

    @Test fun sshPromptAndExternalClearNeverLeaveEmptyDestination() {
        content { MegaProxyNavHost(activity, readConnectionStats = { null }) }
        compose.runOnIdle { SshHostKeyPromptState.show(PendingSshHostKey(store.activeProfileId(), "destination", "ssh-ed25519", "SHA256:test", false, false)) }
        node(R.string.trust_and_connect).assertIsDisplayed()
        compose.runOnIdle { SshHostKeyPromptState.clear() }
        compose.onNodeWithTag("screen-main").assertIsDisplayed()
        node(R.string.trust_and_connect).assertDoesNotExist()
    }
}
