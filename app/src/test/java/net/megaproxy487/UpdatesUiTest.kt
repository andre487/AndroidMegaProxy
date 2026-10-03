package net.megaproxy487

import androidx.compose.ui.test.*
import org.junit.Assert.*
import org.junit.Test

class UpdatesUiTest : MainUiTestBase() {
    @Test fun unknownSourceRequiresChoiceAndGithubDownloadRequiresConsent() {
        activity.getSharedPreferences("updates", 0).edit().clear().commit()
        var checks = 0
        var downloads = 0
        val model = UpdatesViewModel(activity.application, checkUpdate = {
            checks++
            AppUpdate(it, "0.1.2")
        }, downloadUpdate = {
            downloads++
            throw IllegalStateException("simulated failure")
        })
        content { UpdatesScreen(activity, {}, model) }
        node(R.string.update_check).assertIsNotEnabled()
        node(R.string.update_github).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, checks) }
        node(R.string.update_check).performScrollTo().performClick()
        node(R.string.update_download).performScrollTo().performClick()
        node(R.string.update_github_consent).assertIsDisplayed()
        node(R.string.cancel).performClick()
        compose.runOnIdle { assertEquals(0, downloads) }
        node(R.string.update_download).performScrollTo().performClick()
        compose.onNode(hasText(activity.uiText(R.string.update_download)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        node(R.string.update_failed).assertExists()
        compose.runOnIdle { assertEquals(1, downloads); assertNull(model.apk) }
        assertEquals(UpdateSource.GITHUB, AppUpdates(activity).source())
    }

    @Test fun fdroidNeverDownloadsAndFailureDoesNotSwitchSource() {
        val model = UpdatesViewModel(activity.application, checkUpdate = { throw UiException(R.string.update_not_published) },
            downloadUpdate = { error("Must not download") })
        model.select(UpdateSource.FDROID)
        content { UpdatesScreen(activity, {}, model) }
        node(R.string.update_check).performScrollTo().performClick()
        node(R.string.update_not_published).assertExists()
        assertEquals(UpdateSource.FDROID, model.source)
        node(R.string.update_download).assertDoesNotExist()
    }

    @Test fun fdroidOffersStoreInsteadOfApkDownload() {
        val model = UpdatesViewModel(activity.application, checkUpdate = { AppUpdate(it, "0.1.2") },
            downloadUpdate = { error("Must not download") })
        model.select(UpdateSource.FDROID)
        content { UpdatesScreen(activity, {}, model) }
        node(R.string.update_check).performScrollTo().performClick()
        node(R.string.update_in_fdroid).assertExists()
        node(R.string.update_download).assertDoesNotExist()
        node(R.string.update_in_fdroid).performScrollTo().performClick()
        compose.runOnIdle {
            val intent = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(FDROID_APP_URL, intent.data.toString())
            assertNull(intent.`package`)
            model.download()
            assertNull(model.apk)
        }
    }
}
