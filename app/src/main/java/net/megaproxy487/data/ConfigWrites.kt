package net.megaproxy487.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConfigWriteStatus(val pending: Int = 0, val failed: Boolean = false)

/** One ordered queue owned by the application, never by a disappearing Compose screen. */
class ConfigWriteQueue(dispatcher: CoroutineDispatcher) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val generations = mutableMapOf<String, Long>()
    private val failures = linkedMapOf<String, () -> Unit>()
    private val mutableStatus = MutableStateFlow(ConfigWriteStatus())
    val status = mutableStatus.asStateFlow()

    @Synchronized
    fun submit(key: String, write: () -> Unit) {
        mutableStatus.value = mutableStatus.value.copy(pending = mutableStatus.value.pending + 1)
        val generation = generations[key] ?: 0L
        scope.launch {
            val current = synchronized(this@ConfigWriteQueue) { generation == (generations[key] ?: 0L) }
            try {
                val result = if (current) operationResult(write) else null
                synchronized(this@ConfigWriteQueue) {
                    if (generation == (generations[key] ?: 0L) && result != null) {
                        if (result.isSuccess) failures.remove(key) else failures[key] = write
                    }
                }
            } finally {
                synchronized(this@ConfigWriteQueue) {
                    mutableStatus.value = ConfigWriteStatus(mutableStatus.value.pending - 1, failures.isNotEmpty())
                }
            }
        }
    }

    /** A deleted entity must not be recreated by retrying an older failed draft write. */
    @Synchronized
    fun discard(key: String) {
        generations[key] = (generations[key] ?: 0L) + 1
        failures.remove(key)
        mutableStatus.value = mutableStatus.value.copy(failed = failures.isNotEmpty())
    }

    @Synchronized
    fun retry() {
        if (mutableStatus.value.pending != 0) return
        failures.toMap().forEach { (key, write) -> submit(key, write) }
    }
}

val ConfigWrites = ConfigWriteQueue(ConfigIoDispatcher)
