package net.megaproxy487

import android.app.Application
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.*
import net.megaproxy487.vpn.*
import org.junit.Assert.*
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowVpnService

class MainScreenUiTest : MainUiTestBase() {
    private fun screen() = content { MainScreen(activity, {}, {}, {}, readConnectionStats = { null }) }
    private fun command(action: String) {
        drainConfigIo()
        val application = shadowOf(activity.application as Application)
        val received = application.nextStartedService
        assertEquals("net.megaproxy487.$action", received?.action)
        assertEquals(ProxyVpnService::class.java.name, received?.component?.className)
        assertNull("Unexpected extra service command", application.nextStartedService)
    }

    private fun noCommand() {
        drainConfigIo()
        assertNull(shadowOf(activity.application as Application).nextStartedService)
    }

    @Test fun connectDispatchesStartAndPersistsDesiredState() {
        screen()
        node(R.string.connect).performScrollTo().assertIsEnabled().performClick()
        command("START_MANUAL")
        assertTrue(store.isConnectionDesired())
    }

    @Test fun grantingVpnPermissionStartsRequestedConnection() {
        val consent = Intent("test.VPN_CONSENT")
        ShadowVpnService.setPrepareResult(consent)
        screen()
        node(R.string.connect).performScrollTo().performClick()
        noCommand()
        compose.runOnIdle {
            ShadowVpnService.setPrepareResult(null)
            shadowOf(activity).receiveResult(consent, android.app.Activity.RESULT_OK, null)
        }
        command("START_MANUAL")
        assertTrue(store.isConnectionDesired())
    }

    @Test fun selectingProfileWhileDisconnectedPersistsWithoutStartingVpn() {
        val other = store.cloneProfile(store.activeProfileId())!!.copy(name = "Secondary")
        store.saveProfile(other)
        screen()
        compose.onNodeWithText("Primary").performClick()
        compose.onNodeWithText("Secondary").performClick()
        saved()
        assertEquals(other.id, store.activeProfileId())
        compose.onNodeWithText("Secondary").assertIsDisplayed()
        noCommand()
    }

    @Test fun longProfileMenuFitsSafeContentAndLastProfileCanBeSelected() {
        val primary = store.activeProfile()
        repeat(30) { index -> store.saveProfile(primary.copy(id = "menu-$index", name = "Menu profile $index"), createIfMissing = true) }
        assertEquals(31, store.sortedProfiles().size)
        screen()
        compose.runOnIdle {
            val density = activity.resources.displayMetrics.density
            ViewCompat.dispatchApplyWindowInsets(activity.window.decorView,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (100 * density).toInt(), 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (200 * density).toInt()))
                    .build())
        }
        compose.onNodeWithText("Primary").performClick()
        val menuBounds = compose.onNode(isPopup()).getUnclippedBoundsInRoot()
        val menuHeight = menuBounds.bottom - menuBounds.top
        val screenHeight = activity.resources.displayMetrics.let { it.heightPixels / it.density }.dp
        assertTrue("Menu must leave room for system bars and app bar: $menuHeight", menuHeight <= screenHeight - 364.dp)
        compose.onNodeWithText("Menu profile 29").performScrollTo().assertIsDisplayed().performClick()
        saved()
        assertEquals("menu-29", store.activeProfileId())
        noCommand()
    }

    @Test fun invalidProfileRequiresConfigurationBeforeConnect() {
        store.saveProfile(store.activeProfile().let { it.copy(config = it.config.copy(host = "")) })
        var edited: String? = null
        content { MainScreen(activity, {}, {}, { edited = it }, readConnectionStats = { null }) }
        node(R.string.connect).performScrollTo().assertIsNotEnabled()
        node(R.string.configure).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(store.activeProfileId(), edited) }
    }

    @Test fun cancellingConnectRemainsPossibleAndTestMenuIsDisabled() {
        VpnRuntimeState.update(VpnConnectionState.CONNECTING)
        screen()
        icon(R.string.main_actions).performClick()
        node(R.string.test_connection).assertIsNotEnabled()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        node(R.string.disconnect).performScrollTo().assertIsEnabled().performClick()
        command("STOP")
        assertFalse(store.isConnectionDesired())
    }

    @Test fun connectedSessionOffersReconnectAndDisconnectWithoutLoadingJni() {
        VpnRuntimeState.update(VpnConnectionState.CONNECTED)
        screen()
        node(R.string.reconnect).performScrollTo().performClick()
        command("RECONNECT")
        node(R.string.disconnect).performScrollTo().performClick()
        command("STOP")
        assertFalse(store.isConnectionDesired())
    }

    @Test fun alwaysOnDisablesManualConnectionControl() {
        Settings.Secure.putString(activity.contentResolver, "always_on_vpn_app", activity.packageName)
        screen()
        node(R.string.connect).performScrollTo().assertIsNotEnabled()
    }

    @Test fun anotherAlwaysOnProviderShowsConflictInsteadOfStartingVpn() {
        Settings.Secure.putString(activity.contentResolver, "always_on_vpn_app", "other.vpn")
        screen()
        node(R.string.connect).performScrollTo().performClick()
        node(R.string.always_on_conflict_title).assertIsDisplayed()
        noCommand()
    }

    @Test fun deniedPermissionShowsDenialRatherThanAlwaysOnConflict() {
        val consent = Intent("test.VPN_CONSENT")
        ShadowVpnService.setPrepareResult(consent)
        screen()
        node(R.string.connect).performScrollTo().performClick()
        compose.runOnIdle { shadowOf(activity).receiveResult(consent, android.app.Activity.RESULT_CANCELED, null) }
        node(R.string.vpn_permission_denied).assertIsDisplayed()
        node(R.string.always_on_conflict_title).assertDoesNotExist()
        noCommand()
        assertFalse(store.isConnectionDesired())
    }
}
