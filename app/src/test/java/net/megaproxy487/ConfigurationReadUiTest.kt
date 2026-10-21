package net.megaproxy487

import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import net.megaproxy487.data.ConfigIoDispatcher
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

class ConfigurationReadUiTest : MainUiTestBase() {
    @Test fun slowConfigurationReadLeavesUiResponsiveAndDoesNotExposeDefaults() {
        val tasks = LinkedBlockingQueue<Runnable>()
        ConfigIoDispatcher.delegate = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val worker = Executors.newSingleThreadExecutor()
        val onMain = AtomicBoolean()
        try {
            compose.setContent {
                val configuration = readConfiguration("slow") {
                    onMain.set(Looper.myLooper() == Looper.getMainLooper())
                    "Loaded configuration"
                }
                Text(configuration ?: "Loading configuration")
            }
            compose.onNodeWithText("Loaded configuration").assertDoesNotExist()
            worker.submit(checkNotNull(tasks.poll(5, TimeUnit.SECONDS))).get(5, TimeUnit.SECONDS)
            assertFalse(onMain.get())
            waitForText("Loaded configuration")
        } finally {
            ConfigIoDispatcher.delegate = Dispatchers.Unconfined
            worker.shutdownNow()
        }
    }
}
