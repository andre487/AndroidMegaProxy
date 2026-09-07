package net.megaproxy487

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigWrites
import net.megaproxy487.model.ProxyType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Real activity/navigation/storage, synthetic profiles, no dependency on a working proxy. */
@RunWith(Parameterized::class)
class MainScreenUiTest(private val language: AppLanguage, private val fontScale: String) {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var oldFontScale: String

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim() }

    @Before fun launch() {
        oldFontScale = shell("settings get system font_scale")
        shell("settings put system font_scale $fontScale")
        AppLanguageManager.set(context, language)
        context.getSharedPreferences("battery_optimization_reminder", Context.MODE_PRIVATE).edit()
            .putLong("last_request_at", System.currentTimeMillis()).commit()
        val store = ConfigStore(context)
        val profile = store.profiles().first()
        store.saveProfile(profile.copy(name = "UI test profile with a deliberately long name",
            config = profile.config.copy(type = ProxyType.HTTPS_JUMP, host = "", jumpHost = "")))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        screen("main")
    }

    @After fun close() {
        if (::scenario.isInitialized) scenario.close()
        if (::oldFontScale.isInitialized) {
            if (oldFontScale == "null") shell("settings delete system font_scale")
            else shell("settings put system font_scale $oldFontScale")
        }
    }

    private fun text(id: Int) = context.uiText(id)
    private fun screen(route: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("screen-$route").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("screen-$route").assertIsDisplayed()
    }
    private fun openProfiles() {
        compose.onNodeWithContentDescription(text(R.string.main_actions)).performClick()
        compose.onNodeWithText(text(R.string.settings)).performClick()
        screen("settings")
        compose.onNodeWithText(text(R.string.profiles)).performScrollTo().performClick()
        screen("profiles")
    }
    private fun addProfile() {
        val button = hasText(text(R.string.add_profile)) and isEnabled()
        compose.waitUntil(10_000) { compose.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(button).performClick()
        screen("profile-editor")
    }
    private fun back() {
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
    }

    @Test fun mainMenuAndNavigationSurviveRecreation() {
        val screenshotDirectory = File(context.getExternalFilesDir(null), "ui-test-screenshots").apply { mkdirs() }
        File(screenshotDirectory, "main-${language.tag}-$fontScale.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText(text(R.string.connect)).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.test_connection)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.main_actions)).performClick()
        compose.onNodeWithText(text(R.string.test_connection)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.feedback)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings)).performClick()
        screen("settings")
        scenario.recreate()
        screen("settings")
        back()
        screen("main")
        compose.onNodeWithContentDescription(text(R.string.main_actions)).assertIsDisplayed()
    }

    @Test fun editedProfileSurvivesRecreationAndNavigation() {
        openProfiles()
        addProfile()
        val name = "Saved UI draft ${language.tag} $fontScale"
        compose.onNode(hasSetTextAction() and hasText(text(R.string.profile_name_optional)))
            .performScrollTo().performTextReplacement(name)
        compose.waitUntil(10_000) { ConfigWrites.status.value.pending == 0 }
        scenario.recreate()
        screen("profile-editor")
        compose.onNode(hasSetTextAction() and hasText(name)).assertExists()
        back()
        screen("profiles")
        compose.waitUntil(10_000) { ConfigWrites.status.value.pending == 0 }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(name))
        compose.onNodeWithText(name).assertIsDisplayed()
        assertEquals(1, ConfigStore(context).profiles().count { it.name == name })
    }

    @Test fun closingUntouchedDraftDoesNotCreateProfile() {
        val before = ConfigStore(context).profiles().size
        openProfiles()
        addProfile()
        scenario.recreate()
        screen("profile-editor")
        back()
        screen("profiles")
        assertEquals(before, ConfigStore(context).profiles().size)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}, font={1}")
        fun configurations() = listOf(
            arrayOf(AppLanguage.ENGLISH, "1.0"), arrayOf(AppLanguage.RUSSIAN, "1.0"),
            arrayOf(AppLanguage.ENGLISH, "2.0"), arrayOf(AppLanguage.RUSSIAN, "2.0"),
        )
    }
}
