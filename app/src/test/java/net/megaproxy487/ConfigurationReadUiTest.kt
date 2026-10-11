package net.megaproxy487

import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ConfigurationReadUiTest : MainUiTestBase() {
    @Test fun slowConfigurationReadLeavesUiResponsiveAndDoesNotExposeDefaults() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val onMain = AtomicBoolean()
        try {
            compose.setContent {
                val configuration = readConfiguration("slow") {
                    onMain.set(Looper.myLooper() == Looper.getMainLooper())
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    "Loaded configuration"
                }
                if (configuration != null) Text(configuration)
            }
            compose.waitForIdle()
            compose.waitUntil(5_000) { entered.count == 0L }
            compose.onNodeWithText("Loaded configuration").assertDoesNotExist()
            assertFalse(onMain.get())
            release.countDown()
            waitForText("Loaded configuration")
        } finally { release.countDown() }
    }
}
