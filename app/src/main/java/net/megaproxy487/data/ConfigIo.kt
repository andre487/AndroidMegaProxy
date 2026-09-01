package net.megaproxy487.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Ordered background dispatcher for configuration encryption, JSON and persistence. */
val ConfigIoDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
    Thread(task, "megaproxy-config-io").apply { isDaemon = true }
}.asCoroutineDispatcher()
