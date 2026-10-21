package net.megaproxy487.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/** Ordered background dispatcher for configuration encryption, JSON and persistence. */
object ConfigIoDispatcher : CoroutineDispatcher() {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "megaproxy-config-io").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    @Volatile internal var delegate: CoroutineDispatcher = worker
    internal fun reset() { delegate = worker }
    override fun isDispatchNeeded(context: CoroutineContext) = delegate.isDispatchNeeded(context)
    override fun dispatch(context: CoroutineContext, block: Runnable) = delegate.dispatch(context, block)
}
