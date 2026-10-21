package net.megaproxy487

import androidx.compose.ui.test.*
import net.megaproxy487.data.ConfigStore
import org.junit.Assert.assertEquals
import org.junit.Test

class MtuSettingsUiTest : MainUiTestBase() {
    @Test fun presetsAndValidatedCustomValuePersistWithoutChangingRouting() {
        content { MtuSettingsScreen(activity) {} }
        node(R.string.mtu_1400).assertIsSelected()
        for ((label, mtu) in listOf(R.string.mtu_1280 to 1280, R.string.mtu_1500 to 1500, R.string.mtu_1400 to 1400)) {
            node(label).performClick()
            saved()
            assertEquals(mtu, ConfigStore(activity).globalConnectionSettings().mtu)
        }
        node(R.string.mtu_custom).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("1279")
        node(R.string.apply_new_settings).assertIsNotEnabled()
        saved()
        assertEquals(1400, store.globalConnectionSettings().mtu)
        compose.onNode(hasSetTextAction()).performTextReplacement("65536")
        node(R.string.apply_new_settings).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("1420")
        node(R.string.apply_new_settings).performClick()
        saved()
        assertEquals(1420, ConfigStore(activity).effectiveConnectionProfile().config.mtu)
        assertEquals(true, store.globalConnectionSettings().routeAllApps)
    }
}
