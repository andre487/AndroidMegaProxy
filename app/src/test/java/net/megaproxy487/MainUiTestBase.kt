package net.megaproxy487

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import net.megaproxy487.data.ConfigStore
import net.megaproxy487.data.ConfigWrites
import net.megaproxy487.model.GlobalConnectionSettings
import net.megaproxy487.vpn.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowVpnService
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w411dp-h891dp")
@LooperMode(LooperMode.Mode.PAUSED)
abstract class MainUiTestBase {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    protected val activity get() = compose.activity
    protected val store get() = ConfigStore(activity)
    protected fun text(id: Int) = activity.uiText(id)
    protected fun node(id: Int) = compose.onNodeWithText(text(id))
    protected fun icon(id: Int) = compose.onNodeWithContentDescription(text(id))
    protected fun content(block: @Composable () -> Unit) = compose.setContent { MaterialTheme { block() } }
    protected fun saved() {
        compose.waitUntil(10_000) { ConfigWrites.status.value.pending == 0 }
        compose.waitForIdle()
    }
    protected fun waitForText(value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }
    protected fun seed(name: String = "Primary") = store.activeProfile().let {
        it.copy(name = name, config = it.config.copy(host = "proxy.example", username = "user", password = "test-password"))
    }.also { store.saveProfile(it) }

    @Before fun preparePlatform() {
        Security.removeProvider("AndroidKeyStore")
        UiTestKeyStore.keys.clear()
        Security.addProvider(UiTestKeyStoreProvider())
        shadowOf(activity.application as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        VpnRuntimeState.update(VpnConnectionState.DISCONNECTED)
        VpnRuntimeState.updateSystem(false, false, "")
        VpnRuntimeState.updateNetworkWarning(null)
        SshHostKeyPromptState.clear()
        TestDiagnosticLog.reset()
        store.saveGlobalConnectionSettings(GlobalConnectionSettings(routeAllApps = true))
        ShadowVpnService.setPrepareResult(null)
        seed()
    }

    @After fun finishPlatform() {
        saved()
        Security.removeProvider("AndroidKeyStore")
        UiTestKeyStore.keys.clear()
    }
}
