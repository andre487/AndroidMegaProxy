package net.megaproxy487.vpn

import android.app.Application
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DiagnosticExportTest {
    @Test fun stalledDestinationDoesNotBlockCrashWritesOrIncludeLaterEntries() {
        val app = RuntimeEnvironment.getApplication()
        PersistentDiagnosticLog.initialize(app, 3)
        File(app.filesDir, "logs/diagnostic.log").appendText("before export\n")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                super.write(bytes, offset, length)
            }
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val export = workers.submit { PersistentDiagnosticLog.copyTo(output) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            workers.submit { PersistentDiagnosticLog.writeCrash(Thread.currentThread(), IllegalStateException()) }
                .get(3, TimeUnit.SECONDS)
            release.countDown()
            export.get(3, TimeUnit.SECONDS)
            assertTrue(output.toString().contains("before export"))
            assertFalse(output.toString().contains("event=uncaught_exception"))
            assertTrue(PersistentDiagnosticLog.readTail(64 * 1024).contains("event=uncaught_exception"))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }
}
