package net.megaproxy487

import androidx.compose.ui.test.*
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.model.SourceValidationLevel
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

    @Test fun legacyEnabledSettingKeepsAdvancedAndInvalidLevelsPreserveStorage() {
        val prefs = activity.getSharedPreferences("proxy_config", android.content.Context.MODE_PRIVATE)
        val key = "global_connection_settings_v1"
        val json = org.json.JSONObject(prefs.getString(key, null)!!)
        json.put("strictSourceValidation", true).remove("sourceValidationLevel")
        prefs.edit().putString(key, json.toString()).commit()
        assertEquals(SourceValidationLevel.ADVANCED, ConfigStore(activity).globalConnectionSettings().sourceValidationLevel)
        json.put("sourceValidationLevel", "unknown")
        val malformed = json.toString()
        prefs.edit().putString(key, malformed).commit()
        assertThrows(UiException::class.java) { ConfigStore(activity).globalConnectionSettings() }
        assertEquals(malformed, prefs.getString(key, null))
    }

    @Test fun protectionIsOptInAndPersistsAsLocalSetting() {
        assertFalse(store.globalConnectionSettings().strictSourceValidation)
        content { SplitTunnelScreen(activity) {} }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(activity.getString(R.string.strict_source_title)))
        node(R.string.strict_source_title).performClick()
        saved()
        assertTrue(ConfigStore(activity).globalConnectionSettings().strictSourceValidation)
        assertTrue(ConfigStore(activity).effectiveConnectionProfile().config.strictSourceValidation)
        assertEquals(SourceValidationLevel.STANDARD, ConfigStore(activity).globalConnectionSettings().sourceValidationLevel)
        node(R.string.source_level_standard).assertIsSelected()
        node(R.string.source_level_advanced).performClick()
        saved()
        assertEquals(SourceValidationLevel.ADVANCED, ConfigStore(activity).globalConnectionSettings().sourceValidationLevel)
        assertEquals(SourceValidationLevel.ADVANCED, ConfigStore(activity).effectiveConnectionProfile().config.sourceValidationLevel)
        node(R.string.strict_source_title).performClick()
        saved()
        assertFalse(ConfigStore(activity).globalConnectionSettings().strictSourceValidation)
    }
}
