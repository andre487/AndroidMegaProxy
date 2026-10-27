package net.megaproxy487

import androidx.compose.ui.test.*
import net.megaproxy487.data.ConfigStore
import org.junit.Assert.*
import org.junit.Test

class StrictSourceSettingsUiTest : MainUiTestBase() {
    @Test @org.robolectric.annotation.Config(sdk = [26])
    fun unsupportedDevicesCannotEnableProtection() {
        content { SplitTunnelScreen(activity) {} }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(activity.getString(R.string.strict_source_title)))
        compose.onNode(isToggleable() and hasText(activity.getString(R.string.strict_source_title))).assertIsNotEnabled()
        assertFalse(store.globalConnectionSettings().strictSourceValidation)
    }

    @Test fun protectionIsOptInAndPersistsAsLocalSetting() {
        assertFalse(store.globalConnectionSettings().strictSourceValidation)
        content { SplitTunnelScreen(activity) {} }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(activity.getString(R.string.strict_source_title)))
        node(R.string.strict_source_title).performClick()
        saved()
        assertTrue(ConfigStore(activity).globalConnectionSettings().strictSourceValidation)
        assertTrue(ConfigStore(activity).effectiveConnectionProfile().config.strictSourceValidation)
        node(R.string.strict_source_title).performClick()
        saved()
        assertFalse(ConfigStore(activity).globalConnectionSettings().strictSourceValidation)
    }
}
