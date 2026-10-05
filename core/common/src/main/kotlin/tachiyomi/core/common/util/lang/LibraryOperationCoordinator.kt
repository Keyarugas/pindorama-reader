package tachiyomi.core.common.util.lang

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Process-local admission, before any SQLite transaction. Participants must acquire an operation
 * before reading the state on which their writes depend, and retain it through their final effect.
 * It is not a durable queue, a database lock, or a substitute for revalidating retained UI models.
 */
class LibraryOperationCoordinator {
    private val monitor = Any()
    private var active = 0
    private var exclusiveRequested = false
    private var changed = CompletableDeferred<Unit>()
    private var epoch = 0L

    /** Capture with a retained snapshot. Recheck inside operation(), before reading or writing. */
    val generation: Long get() = synchronized(monitor) { epoch }

    private class Lease(
        val coordinator: LibraryOperationCoordinator,
        val readOnly: Boolean = false,
    ) : AbstractCoroutineContextElement(Key) {
        @Volatile var valid = true

        companion object Key : CoroutineContext.Key<Lease>
    }

    suspend fun <T> operation(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val inherited = currentCoroutineContext()[Lease]
        if (inherited?.coordinator === this) {
            check(inherited.valid) { "EXPIRED_LIBRARY_OPERATION" }
            check(!inherited.readOnly) { "LIBRARY_OPERATION_IN_SNAPSHOT" }
            return block()
        }
        while (true) {
            val wait = synchronized(monitor) {
                if (exclusiveRequested) {
                    changed
                } else {
                    active++
                    null
                }
            }
            if (wait == null) break
            wait.await()
        }
        val lease = Lease(this)
        try {
            return withContext(lease) { block() }
        } finally {
            lease.valid = false
            val signal = synchronized(monitor) {
                active--
                nextSignal()
            }
            signal.complete(Unit)
        }
    }

    /**
     * Waits for the admission gate, then drains participants and reads alone. Unlike exclusive(),
     * this read-only boundary never invalidates generations. Waiting remains cancellable.
     * Nesting is rejected: promoting a participant could deadlock or expose another writer.
     */
    suspend fun <T> snapshot(block: suspend () -> T): T {
        check(currentCoroutineContext()[Lease]?.coordinator !== this) { "NESTED_LIBRARY_SNAPSHOT" }
        currentCoroutineContext().ensureActive()
        while (true) {
            val wait = synchronized(monitor) {
                if (exclusiveRequested) {
                    changed
                } else {
                    exclusiveRequested = true
                    null
                }
            }
            if (wait == null) break
            wait.await()
        }
        val lease = Lease(this, readOnly = true)
        try {
            awaitParticipants()
            currentCoroutineContext().ensureActive()
            return withContext(lease) { block() }
        } finally {
            releaseGate(lease, invalidateGeneration = false)
        }
    }

    /**
     * Refuses an already reserved snapshot/restore gate immediately; drains operations for at most
     * [waitMillis]. Timeout covers admission only, never interrupts a transaction arbitrarily.
     * Compatibility checks belong inside [block], before beginning a transaction.
     */
    suspend fun <T> exclusive(waitMillis: Long = 30_000, block: suspend () -> T): T {
        require(waitMillis > 0)
        check(currentCoroutineContext()[Lease]?.coordinator !== this) { "NESTED_LIBRARY_EXCLUSIVE" }
        currentCoroutineContext().ensureActive()
        synchronized(monitor) {
            if (exclusiveRequested) throw Unavailable()
            exclusiveRequested = true
        }
        val lease = Lease(this)
        var applicationStarted = false
        try {
            val acquired = withTimeoutOrNull(waitMillis) {
                awaitParticipants()
                true
            } ?: false
            if (!acquired) throw Unavailable()
            currentCoroutineContext().ensureActive()
            return withContext(lease) {
                applicationStarted = true
                block()
            }
        } finally {
            releaseGate(lease, invalidateGeneration = applicationStarted)
        }
    }

    private suspend fun awaitParticipants() {
        while (true) {
            val wait = synchronized(monitor) { if (active == 0) null else changed }
            if (wait == null) return
            wait.await()
        }
    }

    private fun releaseGate(lease: Lease, invalidateGeneration: Boolean) {
        lease.valid = false
        val signal = synchronized(monitor) {
            // Invalidate snapshots captured before or during restore, even on failure.
            // Publish atomically with admission release, before waking participants.
            if (invalidateGeneration) epoch++
            exclusiveRequested = false
            nextSignal()
        }
        signal.complete(Unit)
    }

    // Called under monitor; resume waiters outside it (including Unconfined continuations).
    private fun nextSignal(): CompletableDeferred<Unit> = changed.also { changed = CompletableDeferred() }

    class Unavailable : Exception("LIBRARY_COORDINATION_UNAVAILABLE")

    companion object {
        val shared = LibraryOperationCoordinator()
    }
}

/** The producer owns admission, not the coroutine awaiting this Deferred. */
fun <T> CoroutineScope.libraryAsync(
    coordinator: LibraryOperationCoordinator = LibraryOperationCoordinator.shared,
    block: suspend CoroutineScope.() -> T,
): Deferred<T> = async {
    coordinator.operation { coroutineScope(block) }
}
