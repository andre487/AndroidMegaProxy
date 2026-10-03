package net.megaproxy487

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = Application::class)
class InstallerUpdateSourceTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun clearSource() {
        app.getSharedPreferences("updates", 0).edit().clear().commit()
    }

    @Suppress("DEPRECATION")
    private fun installer(name: String?) {
        if (Build.VERSION.SDK_INT >= 30) {
            shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, name, name)
        } else {
            app.packageManager.setInstallerPackageName(app.packageName, name)
        }
    }

    @Suppress("DEPRECATION")
    private fun handlesRepository(name: String, scheme: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://f-droid.org/repo"))
            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(name)
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = name
                this.name = "$name.RepositoryActivity"
                exported = true
                enabled = true
            }
        })
    }

    @Test fun detectsAlternativeClientWithEitherRepositoryScheme() {
        for (scheme in listOf("fdroidrepo", "fdroidrepos")) {
            val name = "org.example.$scheme"
            installer(name)
            handlesRepository(name, scheme)
            assertEquals(UpdateSource.FDROID, AppUpdates(app).source())
        }
    }

    @Test fun unrelatedFdroidClientDoesNotChangeBrowserInstallerSource() {
        handlesRepository("org.example.store", "fdroidrepos")
        installer("com.android.chrome")
        assertEquals(UpdateSource.GITHUB, AppUpdates(app).source())
    }

    @Test fun unknownInstallerRequiresChoiceEvenWithFdroidInstalled() {
        handlesRepository("org.example.store", "fdroidrepo")
        installer(null)
        assertNull(AppUpdates(app).source())
    }

    @Test fun manualSourceTakesPriorityAndOfficialPrivilegedInstallerStillWorks() {
        installer("org.fdroid.fdroid.privileged")
        assertEquals(UpdateSource.FDROID, AppUpdates(app).source())
        AppUpdates(app).select(UpdateSource.GITHUB)
        assertEquals(UpdateSource.GITHUB, AppUpdates(app).source())
    }
}
