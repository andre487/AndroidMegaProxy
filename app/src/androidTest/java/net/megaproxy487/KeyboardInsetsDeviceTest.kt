package net.megaproxy487

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.model.DnsProvider
import net.megaproxy487.model.ProxyType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local real-IME checks. Refuse to touch the installed production app or its profiles. */
@RunWith(AndroidJUnit4::class)
class KeyboardInsetsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val store get() = ConfigStore(context)

    @Before fun prepareIsolatedApp() {
        check(context.packageName == "net.megaproxy487.keyboardreview") { "Build the isolated keyboardreview APK first" }
        AppLanguageManager.set(context, if (arguments.getString("language") == "ru") AppLanguage.RUSSIAN else AppLanguage.ENGLISH)
        context.getSharedPreferences("battery_optimization_reminder", Context.MODE_PRIVATE)
            .edit().putLong("last_request_at", System.currentTimeMillis()).commit()
        val profile = store.activeProfile()
        store.saveProfile(profile.copy(name = "Keyboard fixture", config = profile.config.copy(
            type = ProxyType.SSH, host = "proxy.example", port = 22,
            username = "fixture", password = "fixture-password", privateKey = "fixture\n".repeat(12),
            dnsProvider = DnsProvider.CUSTOM, customDohUrl = "https://dns.example/dns-query",
        )))
        store.saveGlobalConnectionSettings(store.globalConnectionSettings().copy(routeAllApps = false))
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        val landscape = arguments.getString("orientation") == "landscape"
        scenario.onActivity {
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var oriented = false
        while (!oriented && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity {
                oriented = (it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) == landscape
            }
            SystemClock.sleep(50)
        }
        assertTrue("Activity did not rotate", oriented)
        // Configuration can be updated on the old Activity before its recreation finishes.
        scenario.recreate()
        scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        instrumentation.waitForIdleSync()
        device.waitForIdle()
    }

    @After fun closeIsolatedApp() {
        if (::scenario.isInitialized) {
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "last.xml"))
            scenario.close()
        }
    }

    @Test fun splitSearchAboveKeyboard() = checkField("split", R.string.search_applications, "Chrome")
    @Test fun editorDnsAboveKeyboard() = checkField("editor", R.string.custom_doh_url, "https://dns.example/dns-query")
    @Test fun invalidPortAboveKeyboard() = checkField("editor", R.string.port, "70000")
    @Test fun subscriptionIntervalAboveKeyboard() = checkField("subscription", R.string.subscription_interval, "120")
    @Test fun privateKeyAboveKeyboard() = checkField("editor", R.string.private_key_optional, null)

    private fun checkField(screen: String, labelId: Int, input: String?) {
        var label = ""
        val draft = SubscriptionDraft().apply {
            loaded = true; authenticated = true; url = "https://feed.example/config"
        }
        scenario.onActivity { activity ->
            label = activity.uiText(labelId)
            activity.setContent { KeyboardAwareLayout {
                val scale = arguments.getString("fontScale")?.toFloat() ?: 1f
                val configuration = Configuration(LocalConfiguration.current).apply { fontScale = scale }
                CompositionLocalProvider(
                    LocalDensity provides Density(LocalDensity.current.density, scale),
                    LocalConfiguration provides configuration,
                ) {
                    MaterialTheme {
                        when (screen) {
                            "split" -> SplitTunnelScreen(activity, {})
                            "editor" -> ProfileEditorScreen(activity, store.activeProfileId(), {})
                            "subscription" -> ConfigSubscriptionScreen(activity, {}, draft)
                        }
                    }
                }
            } }
        }
        val anchor = activityText(when (screen) {
            "split" -> R.string.routing_mode
            "editor" -> R.string.profile_name_optional
            else -> R.string.subscription_url
        })
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(anchor)).fetchSemanticsNodes().isNotEmpty()
        }
        val field = hasText(label) and hasSetTextAction()
        if (screen != "subscription") {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(field)
        }
        compose.onNode(field).performScrollTo().performClick()
        compose.waitForIdle()
        scenario.onActivity { activity ->
            val view = activity.window.decorView.findFocus()
            assertNotNull("Activity focus missing", view)
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var imeBottom = 0
        while (imeBottom == 0 && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                imeBottom = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            }
            SystemClock.sleep(50)
        }
        assertTrue("Keyboard did not open", imeBottom > 0)
        // Real IME animation and Compose relocation must settle before geometry is checked.
        SystemClock.sleep(1_000)
        device.waitForIdle()
        compose.onNode(field).assertIsFocused()
        if (input != null) {
            compose.onNode(field).performTextReplacement(input)
            compose.waitForIdle()
            SystemClock.sleep(500)
        }
        compose.onNode(field).assertIsFocused()
        var keyboardTop = 0
        var density = 1f
        scenario.onActivity { activity ->
            val view = activity.window.decorView
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val insets = ViewCompat.getRootWindowInsets(view)!!
            keyboardTop = location[1] + view.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            density = activity.resources.displayMetrics.density
        }
        val bounds = compose.onNode(field).fetchSemanticsNode().boundsInWindow
        val evidence = "${screen}-${context.resources.getResourceEntryName(labelId)}"
        File(context.getExternalFilesDir(null), "$evidence.txt").writeText("field=$bounds keyboardTop=$keyboardTop density=$density")
        device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "$evidence.xml"))
        assertTrue("Field under IME: $bounds, keyboard top $keyboardTop", bounds.bottom <= keyboardTop + 1)
        assertTrue("Field collapsed: $bounds", bounds.height >= 48 * density)
        // Use the platform IME API on API 26 too; selection mode can consume Back.
        scenario.onActivity { activity ->
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        val hideDeadline = SystemClock.elapsedRealtime() + 5_000
        var hidden = false
        while (!hidden && SystemClock.elapsedRealtime() < hideDeadline) {
            scenario.onActivity { activity ->
                hidden = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom == 0
            }
            SystemClock.sleep(50)
        }
        assertTrue("Keyboard did not close", hidden)
        val back = activityText(R.string.back)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription(back)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun activityText(id: Int): String {
        var result = ""
        scenario.onActivity { result = it.uiText(id) }
        return result
    }
}
