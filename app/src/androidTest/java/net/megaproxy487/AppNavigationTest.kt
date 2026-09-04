package net.megaproxy487

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launchApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("app_language", Context.MODE_PRIVATE)
            .edit().putString("language", "en").commit()
        context.getSharedPreferences("display_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("battery_optimization_reminder", Context.MODE_PRIVATE)
            .edit().putLong("last_request_at", System.currentTimeMillis()).commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun closeApp() {
        scenario.close()
    }

    @Test
    fun settingsAndProfilesShareTheMainActivityBackStack() {
        compose.onNodeWithTag("screen-main").assertIsDisplayed()

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("screen-settings").assertIsDisplayed()

        compose.onNodeWithText("Profiles").performClick()
        compose.onNodeWithTag("screen-profiles").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("screen-settings").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("screen-main").assertIsDisplayed()
    }

    @Test
    fun everySettingsDestinationOpensAndReturnsThroughTheNavHost() {
        compose.onNodeWithText("Settings").performClick()

        listOf(
            "Profiles" to "profiles",
            "Always-on VPN" to "always-on",
            "Fingerprints" to "fingerprints",
            "Split tunneling" to "split-tunnel",
            "Failover" to "failover",
            "Visibility" to "visibility",
            "Diagnostic log" to "diagnostic-log",
        ).forEach { (label, route) ->
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.onNodeWithTag("screen-$route").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("screen-settings").assertIsDisplayed()
        }
    }

    @Test
    fun russianLocaleIsAppliedAcrossNavigation() {
        scenario.close()
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("app_language", Context.MODE_PRIVATE)
            .edit().putString("language", "ru").commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)

        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Профили").assertIsDisplayed()
        compose.onNodeWithText("Диагностический журнал").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun trafficUnitsDefaultToIecAndPersistSiSelection() {
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Traffic units").performScrollTo().performClick()
        compose.onNodeWithText("IEC binary (KiB, MiB, GiB · 1024)").assertIsDisplayed()
        compose.onNodeWithText("SI decimal (KB, MB, GB · 1000)").performClick()
        compose.onNodeWithText(
            "Units used for speed and total traffic · SI decimal (KB, MB, GB · 1000)",
        ).assertIsDisplayed()

        scenario.recreate()

        compose.onNodeWithTag("screen-settings").assertIsDisplayed()
        compose.onNodeWithText(
            "Units used for speed and total traffic · SI decimal (KB, MB, GB · 1000)",
        ).performScrollTo().assertIsDisplayed()
    }
}
