package net.megaproxy487.vpn

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticIoTest {
    @Test fun storageFailuresDoNotEscapeAndLaterWritesCanSucceed() {
        assertFalse(runDiagnosticIo { throw IOException("disk full") })
        assertFalse(runDiagnosticIo { throw SecurityException("access denied") })
        var written = false
        assertTrue(runDiagnosticIo { written = true })
        assertTrue(written)
    }

    @Test(expected = IllegalStateException::class)
    fun programmingErrorsAreNotHidden() {
        runDiagnosticIo { error("unexpected state") }
    }
}
