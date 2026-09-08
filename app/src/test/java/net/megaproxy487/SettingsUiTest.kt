package net.megaproxy487

import androidx.compose.ui.test.*
import net.megaproxy487.model.FailoverMode
import net.megaproxy487.model.TlsProfile
import org.junit.Assert.*
import org.junit.Test

class SettingsUiTest : MainUiTestBase() {
    @Test fun trafficUnitsSelectionIsPersisted() {
        content { SettingsHomeScreen(activity, {}, {}) }
        node(R.string.traffic_units).performScrollTo().performClick()
        node(R.string.traffic_units_si).performClick()
        assertEquals(TrafficUnitSystem.SI, TrafficUnitPreferences.current(activity))
        node(R.string.traffic_units).performScrollTo().performClick()
        node(R.string.traffic_units_iec).performClick()
        assertEquals(TrafficUnitSystem.IEC, TrafficUnitPreferences.current(activity))
    }

    @Test fun globalFailoverIsSavedOnlyAfterConfirmation() {
        content { FailoverSettingsScreen(activity, {}) }
        node(R.string.failover_mode).performClick()
        node(FailoverMode.ALL.titleRes).performClick()
        node(R.string.cancel).performClick()
        saved()
        assertEquals(FailoverMode.DISABLED, store.globalConnectionSettings().failoverMode)
        node(R.string.failover_mode).performClick()
        node(FailoverMode.ALL.titleRes).performClick()
        node(R.string.enable).performClick()
        saved()
        assertEquals(FailoverMode.ALL, store.globalConnectionSettings().failoverMode)
    }

    @Test fun fingerprintChoicePersists() {
        content { TlsFingerprintScreen(activity, {}) }
        node(R.string.https_tls_ja3_profile).performScrollTo().performClick()
        node(TlsProfile.FIREFOX_ANDROID.titleRes).performClick()
        saved()
        assertEquals(TlsProfile.FIREFOX_ANDROID, store.globalConnectionSettings().tlsProfile)
    }

    @Test fun selectedAppsModePersistsWithoutSelectingAllApplications() {
        content { SplitTunnelScreen(activity, {}) }
        node(R.string.selected_apps_routing_description).performClick()
        saved()
        assertFalse(store.globalConnectionSettings().routeAllApps)
        assertTrue(store.globalConnectionSettings().selectedPackages.isEmpty())
    }
}
