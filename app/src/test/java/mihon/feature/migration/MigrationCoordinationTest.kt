package mihon.feature.migration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.feature.migration.list.models.MigratingManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.manga.model.Manga

/** Exercises the producer API used by MigrationListViewModel, with synthetic network responses. */
class MigrationCoordinationTest {
    @Test
    fun `cancelling waiter does not release actual migration producer or its children`() = runTest {
        val coordinator = LibraryOperationCoordinator()
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val model = MigratingManga(
            Manga.create(),
            0,
            null,
            "synthetic",
            parent.coroutineContext,
            coordinator,
            StandardTestDispatcher(testScheduler),
        )
        val response = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        try {
            val producer = model.produce {
                events += "read"
                launch {
                    response.await()
                    events += "child-write"
                }
                "result"
            }
            val waiter = launch { producer.await() }
            runCurrent()
            waiter.cancelAndJoin()
            assertTrue(producer.isActive)
            val exclusive = launch { coordinator.exclusive { events += "exclusive" } }
            runCurrent()
            assertEquals(listOf("read"), events)
            response.complete(Unit)
            producer.await()
            exclusive.join()
            assertEquals(listOf("read", "child-write", "exclusive"), events)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `producer admitted during exclusive waits before reading or writing`() = runTest {
        val coordinator = LibraryOperationCoordinator()
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val model = MigratingManga(
            Manga.create(),
            0,
            null,
            "synthetic",
            parent.coroutineContext,
            coordinator,
            StandardTestDispatcher(testScheduler),
        )
        val release = CompletableDeferred<Unit>()
        try {
            val exclusive = launch { coordinator.exclusive { release.await() } }
            runCurrent()
            var ran = false
            val producer = model.produce { ran = true }
            runCurrent()
            assertFalse(ran)
            release.complete(Unit)
            exclusive.join()
            producer.await()
            assertTrue(ran)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `producer failure and parent cancellation release admission without cancelling unrelated scope`() = runTest {
        val coordinator = LibraryOperationCoordinator()
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val model = MigratingManga(
            Manga.create(),
            0,
            null,
            "synthetic",
            parent.coroutineContext,
            coordinator,
            StandardTestDispatcher(testScheduler),
        )
        try {
            val failed = model.produce { error("synthetic") }
            assertThrows<IllegalStateException> { failed.await() }
            coordinator.exclusive { }
            val response = CompletableDeferred<Unit>()
            var lateWrite = false
            val pending = model.produce {
                response.await()
                lateWrite = true
            }
            runCurrent()
            parent.cancel()
            pending.join()
            response.complete(Unit)
            runCurrent()
            assertTrue(pending.isCancelled)
            assertFalse(lateWrite)
            coordinator.exclusive { }
        } finally {
            parent.cancel()
        }
    }
}
