package net.megaproxy487

import androidx.compose.ui.test.*
import net.megaproxy487.data.ConfigSubscription
import org.junit.Assert.*
import org.junit.Test

class ConfigSubscriptionUiTest : MainUiTestBase() {
    @Test fun invalidSourceCannotBeSavedAndTrustedSourcePersistsThroughNavigation() {
        content { ConfigSubscriptionScreen(activity, {}) }
        drainConfigIo()
        node(R.string.subscription_url).performTextInput("http://untrusted.example/")
        node(R.string.subscription_save).performScrollTo().performClick()
        node(R.string.subscription_invalid).assertIsDisplayed()
        assertNull(store.subscriptionState())
        node(R.string.subscription_url).performTextReplacement("https://feed.example/config")
        node(R.string.subscription_save).performScrollTo().performClick()
        drainConfigIo()
        compose.waitUntil(10_000) { store.subscriptionState() != null }
        assertEquals("https://feed.example/config", store.subscriptionState()!!.settings.url)
        assertEquals(60, store.subscriptionState()!!.settings.intervalMinutes)
    }

    @Test fun pauseAndRemovalPreserveProfiles() {
        store.saveSubscription(ConfigSubscription("https://feed.example/config", password = "separate-secret"))
        val profiles = store.profiles()
        content { ConfigSubscriptionScreen(activity, {}) }
        drainConfigIo()
        compose.onNodeWithContentDescription(text(R.string.subscription_enabled)).performClick()
        drainConfigIo()
        compose.waitUntil(10_000) { store.subscriptionState()?.settings?.enabled == false }
        node(R.string.subscription_remove).performScrollTo().performClick()
        drainConfigIo()
        compose.waitUntil(10_000) { store.subscriptionState() == null }
        assertEquals(profiles, store.profiles())
    }
}
