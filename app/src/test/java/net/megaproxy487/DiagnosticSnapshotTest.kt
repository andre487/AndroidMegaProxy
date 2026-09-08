package net.megaproxy487

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DiagnosticSnapshotTest {
    @Test fun storageFailureAllowsNextReadToRecover() {
        assertNull(readDiagnosticSnapshot { throw IOException("storage unavailable") })
        assertNull(readDiagnosticSnapshot { throw SecurityException("access denied") })
        assertEquals(listOf("first", "second"), readDiagnosticSnapshot { "first\n\nsecond\n" })
    }

    @Test fun cancellationAndFatalErrorsAreNotMasked() {
        assertThrows(CancellationException::class.java) {
            readDiagnosticSnapshot { throw CancellationException() }
        }
        assertThrows(OutOfMemoryError::class.java) {
            readDiagnosticSnapshot { throw OutOfMemoryError("synthetic") }
        }
    }
}
