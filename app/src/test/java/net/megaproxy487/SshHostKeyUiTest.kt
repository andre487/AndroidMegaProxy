package net.megaproxy487

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CompletableDeferred
import net.megaproxy487.vpn.PendingSshHostKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real Compose UI on the JVM; no VPN, JNI, Keystore or external services are started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class SshHostKeyUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val prompt = PendingSshHostKey("profile", "destination", "ssh-ed25519", "SHA256:test", false, false)
    private fun text(id: Int) = compose.activity.uiText(id)

    @Test fun successfulConfirmationSavesOnceAndDismisses() {
        var saves = 0
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                SshHostKeyReview(prompt, { saves++; true }, { error("unexpected rejection") }, { dismissals++ })
            }
        }
        compose.onNodeWithText(text(R.string.trust_and_connect)).performClick()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(1, dismissals)
        }
    }

    @Test fun savingDisablesActionsAndBackUntilOperationFinishes() {
        val result = CompletableDeferred<Boolean>()
        var saves = 0
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                SshHostKeyReview(prompt, { saves++; result.await() }, { error("unexpected rejection") }, { dismissals++ })
            }
        }
        compose.onNodeWithText(text(R.string.trust_and_connect)).performClick()
        compose.onNodeWithText(text(R.string.trust_and_connect)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.cancel)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.saving_changes)).assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(0, dismissals)
            result.complete(true)
        }
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun failureShowsErrorAndAllowsRetry() {
        var saves = 0
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                SshHostKeyReview(prompt, {
                    if (++saves == 1) throw java.io.IOException("storage unavailable")
                    true
                }, {}, { dismissals++ })
            }
        }
        compose.onNodeWithText(text(R.string.trust_and_connect)).performClick()
        compose.onNodeWithText(text(R.string.ssh_key_save_failed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.trust_and_connect)).assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.ssh_key_save_failed)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(2, saves)
            assertEquals(1, dismissals)
        }
    }

    @Test fun cancelledTestDoesNotSaveAndUsesTestSpecificAction() {
        var rejected = 0
        var dismissed = 0
        compose.setContent {
            MaterialTheme {
                SshHostKeyReview(prompt.copy(testOnly = true, changed = true),
                    { error("cancel must not save") }, { rejected++ }, { dismissed++ })
            }
        }
        compose.onNodeWithText(text(R.string.replace_and_test)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.runOnIdle {
            assertEquals(1, rejected)
            assertEquals(1, dismissed)
        }
    }
}
