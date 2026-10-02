package eu.kanade.tachiyomi.data.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator

class LibraryOperationCoordinatorTest {
    @Test
    fun `snapshots before and during exclusive expire before blocked participants resume`() = runTest {
        val coordinator = LibraryOperationCoordinator()
        val before = coordinator.generation
        val entered = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val exclusive = launch {
            coordinator.exclusive {
                entered.complete(coordinator.generation)
                release.await()
            }
        }
        val during = entered.await()
        val participant = async { coordinator.operation { coordinator.generation } }
        runCurrent()
        assertFalse(participant.isCompleted)
        assertEquals(before, during)
        release.complete(Unit)
        exclusive.join()
        assertEquals(before + 1, participant.await())
        assertEquals(during + 1, coordinator.generation)
    }

    @Test
    fun `independent producer must participate itself because cancelling its waiter does not stop it`() = runTest {
        val coordinator = LibraryOperationCoordinator()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val response = CompletableDeferred<Unit>()
        var state = "original"
        try {
            val producer = scope.async {
                response.await()
                state = "stale-network-result"
            }
            val waiter = launch { coordinator.operation { producer.await() } }
            runCurrent()
            waiter.cancelAndJoin()
            assertTrue(producer.isActive)
            coordinator.exclusive { state = "restored" }
            response.complete(Unit)
            producer.await()
            // Counterexample, NOT a guarantee: wrapping only the waiter is unsafe.
            assertEquals("stale-network-result", state)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `admission alone does not refresh a retained reader snapshot`() = runTest {
        val coordinator = LibraryOperationCoordinator()
        var read = false
        val retainedReadFlag = read
        coordinator.exclusive { read = true }
        coordinator.operation { read = retainedReadFlag }
        // Counterexample: the snapshot needs invalidation or revalidation before applying it.
        assertFalse(read)
    }

    @Test
    fun `exclusive drains network operation and prevents new operations until completion`() = runTest {
        val coordinator = LibraryOperationCoordinator()
        val response = CompletableDeferred<Unit>()
        val finishRestore = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val network = launch {
            coordinator.operation {
                events += "read"
                response.await()
                events += "network-write"
            }
        }
        runCurrent()
        val restore = launch {
            coordinator.exclusive {
                events += "restore"
                finishRestore.await()
            }
        }
        runCurrent()
        val next = launch { coordinator.operation { events += "new-read-and-write" } }
        runCurrent()
        assertEquals(listOf("read"), events)
        response.complete(Unit)
        network.join()
        runCurrent()
        assertEquals(listOf("read", "network-write", "restore"), events)
        finishRestore.complete(Unit)
        restore.join()
        next.join()
        assertEquals(listOf("read", "network-write", "restore", "new-read-and-write"), events)
    }

    @Test
    fun `cancellation while draining releases admission without cancelling existing work`() = runTest {
        val coordinator = LibraryOperationCoordinator()
        val release = CompletableDeferred<Unit>()
        val existing = launch { coordinator.operation { release.await() } }
        runCurrent()
        val generation = coordinator.generation
        var applied = false
        val restore = launch { coordinator.exclusive { applied = true } }
        runCurrent()
        restore.cancelAndJoin()
        assertFalse(applied)
        assertEquals(generation, coordinator.generation)
        assertTrue(existing.isActive)
        coordinator.operation { }
        release.complete(Unit)
        existing.join()
        coordinator.exclusive { }
    }

    @Test
    fun `timeout is bounded and preserves existing operation`() = runTest {
        val coordinator = LibraryOperationCoordinator()
        val release = CompletableDeferred<Unit>()
        val existing = launch { coordinator.operation { release.await() } }
        runCurrent()
        val generation = coordinator.generation
        val restore = async {
            assertThrows<LibraryOperationCoordinator.Unavailable> { coordinator.exclusive(50) { error("unexpected") } }
        }
        advanceTimeBy(51)
        restore.await()
        assertEquals(generation, coordinator.generation)
        assertTrue(existing.isActive)
        release.complete(Unit)
        existing.join()
        coordinator.exclusive { }
    }

    @Test
    fun `failure and cancellation during exclusive application release admission`() = runTest {
        val coordinator = LibraryOperationCoordinator()
        val generation = coordinator.generation
        assertThrows<IllegalStateException> { coordinator.exclusive { error("synthetic") } }
        assertEquals(generation + 1, coordinator.generation)
        coordinator.operation { }
        val release = CompletableDeferred<Unit>()
        val restore = launch { coordinator.exclusive { release.await() } }
        runCurrent()
        restore.cancelAndJoin()
        assertEquals(generation + 2, coordinator.generation)
        coordinator.operation { }
        coordinator.exclusive { }
    }

    @Test
    fun `nested participants do not deadlock behind pending exclusive request`() = runTest {
        val coordinator = LibraryOperationCoordinator()
        val enterNested = CompletableDeferred<Unit>()
        val operation = launch {
            coordinator.operation {
                enterNested.await()
                coordinator.operation { }
            }
        }
        runCurrent()
        val restore = launch { coordinator.exclusive { coordinator.operation { } } }
        runCurrent()
        enterNested.complete(Unit)
        operation.join()
        restore.join()
    }
}
