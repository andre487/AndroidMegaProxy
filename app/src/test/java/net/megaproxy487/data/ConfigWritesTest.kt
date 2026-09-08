package net.megaproxy487.data

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ConfigWritesTest {
    private class ManualDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun retryWhileWritesArePendingDoesNotDuplicateWork() {
        val dispatcher = ManualDispatcher()
        val queue = ConfigWriteQueue(dispatcher)
        var attempts = 0
        queue.submit("profile") { attempts++; error("disk unavailable") }
        dispatcher.drain()
        queue.retry()
        queue.retry()
        assertEquals(1, queue.status.value.pending)
        dispatcher.drain()
        assertEquals(2, attempts)
        assertEquals(ConfigWriteStatus(failed = true), queue.status.value)
    }

    @Test fun deletingBeforeInitialWritePreventsDraftResurrection() {
        val dispatcher = ManualDispatcher()
        val queue = ConfigWriteQueue(dispatcher)
        var writes = 0
        queue.submit("profile:deleted") { writes++ }
        queue.discard("profile:deleted")
        dispatcher.drain()
        assertEquals(0, writes)
        assertEquals(ConfigWriteStatus(), queue.status.value)
        queue.submit("profile:deleted") { writes++ }
        dispatcher.drain()
        assertEquals(1, writes)
    }

    @Test fun successfulWriteForOtherProfileDoesNotHideFailure() {
        val dispatcher = ManualDispatcher()
        val queue = ConfigWriteQueue(dispatcher)
        var fail = true
        var writes = 0
        queue.submit("first") { if (fail) error("disk unavailable") else writes++ }
        queue.submit("second") { writes++ }
        dispatcher.drain()
        assertEquals(1, writes)
        assertEquals(ConfigWriteStatus(failed = true), queue.status.value)
        fail = false
        queue.retry()
        dispatcher.drain()
        assertEquals(2, writes)
        assertEquals(ConfigWriteStatus(), queue.status.value)
    }

    @Test fun queuedWritesSurviveCallerCancellationAndKeepOrder() {
        val dispatcher = ManualDispatcher()
        val queue = ConfigWriteQueue(dispatcher)
        val saved = mutableListOf<Int>()
        val screen = CoroutineScope(Job() + Dispatchers.Unconfined)
        screen.launch {
            queue.submit("profile") { saved += 1 }
            queue.submit("profile") { saved += 2 }
        }
        screen.cancel()
        assertEquals(2, queue.status.value.pending)
        dispatcher.drain()
        assertEquals(listOf(1, 2), saved)
        assertEquals(ConfigWriteStatus(), queue.status.value)
    }

    @Test fun failedWriteCanBeRetried() {
        val dispatcher = ManualDispatcher()
        val queue = ConfigWriteQueue(dispatcher)
        var fail = true
        var saved = false
        queue.submit("profile") { if (fail) error("disk unavailable") else saved = true }
        dispatcher.drain()
        assertTrue(queue.status.value.failed)
        fail = false
        queue.retry()
        dispatcher.drain()
        assertTrue(saved)
        assertEquals(ConfigWriteStatus(), queue.status.value)
    }

    @Test fun deletingEntityDiscardsItsFailedWriteBeforeRetry() {
        val dispatcher = ManualDispatcher()
        val queue = ConfigWriteQueue(dispatcher)
        var attempts = 0
        queue.submit("profile:deleted") { attempts++; error("disk unavailable") }
        dispatcher.drain()
        queue.discard("profile:deleted")
        queue.retry()
        dispatcher.drain()
        assertEquals(1, attempts)
        assertFalse(queue.status.value.failed)
    }

    @Test fun deletionAlsoInvalidatesAnAlreadyQueuedRetry() {
        val dispatcher = ManualDispatcher()
        val queue = ConfigWriteQueue(dispatcher)
        var attempts = 0
        queue.submit("profile:deleted") { attempts++; error("disk unavailable") }
        dispatcher.drain()
        queue.retry()
        queue.discard("profile:deleted")
        dispatcher.drain()
        assertEquals(1, attempts)
        assertEquals(ConfigWriteStatus(), queue.status.value)
    }

    @Test fun newerSuccessfulSnapshotReplacesFailedOlderSnapshot() {
        val dispatcher = ManualDispatcher()
        val queue = ConfigWriteQueue(dispatcher)
        var saved = 0
        queue.submit("profile") { error("old failure") }
        queue.submit("profile") { saved = 2 }
        dispatcher.drain()
        queue.retry()
        dispatcher.drain()
        assertEquals(2, saved)
        assertFalse(queue.status.value.failed)
    }
}
