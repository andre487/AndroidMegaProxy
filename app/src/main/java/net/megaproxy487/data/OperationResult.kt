package net.megaproxy487.data

import java.util.concurrent.CancellationException

/** Recoverable failures may be retried; VM errors must never become empty persisted data. */
internal inline fun <T> operationResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
