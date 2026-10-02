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

    private class Lease(val coordinator: LibraryOperationCoordinator) : AbstractCoroutineContextElement(Key) {
        @Volatile var valid = true

        companion object Key : CoroutineContext.Key<Lease>
    }

    suspend fun <T> operation(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val inherited = currentCoroutineContext()[Lease]
        if (inherited?.coordinator === this) {
            check(inherited.valid) { "EXPIRED_LIBRARY_OPERATION" }
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
     * Refuses another exclusive requester immediately; drains existing operations for at most
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
        try {
            val acquired = withTimeoutOrNull(waitMillis) {
                while (true) {
                    val wait = synchronized(monitor) { if (active == 0) null else changed }
                    if (wait == null) break
                    wait.await()
                }
                true
            } ?: false
            if (!acquired) throw Unavailable()
            currentCoroutineContext().ensureActive()
            // Invalidate even on application failure: a participant must not assume nothing changed.
            synchronized(monitor) { epoch++ }
            return withContext(lease) { block() }
        } finally {
            lease.valid = false
            val signal = synchronized(monitor) {
                exclusiveRequested = false
                nextSignal()
            }
            signal.complete(Unit)
        }
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
