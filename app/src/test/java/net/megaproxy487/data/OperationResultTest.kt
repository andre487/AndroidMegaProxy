package net.megaproxy487.data

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class OperationResultTest {
    @Test fun expectedFailureRemainsAvailableForRetry() {
        val failure = IOException("disk full")
        assertSame(failure, operationResult<Unit> { throw failure }.exceptionOrNull())
        assertEquals("saved", operationResult { "saved" }.getOrThrow())
    }

    @Test fun vmFailureAndCancellationCannotBecomeDefaultConfiguration() {
        for (failure in listOf(OutOfMemoryError("synthetic"), StackOverflowError(), CancellationException())) {
            val thrown = assertThrows(failure.javaClass) {
                operationResult<String> { throw failure }.getOrDefault("empty configuration")
            }
            assertSame(failure, thrown)
        }
    }
}
