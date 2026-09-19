package eu.kanade.tachiyomi.data.backup.create

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * No provider atomicity is assumed. Retention can only run after close and validation succeed.
 * A failed cleanup cannot hide the original failure; retention never triggers deletion of the new copy.
 */
internal suspend fun publishBackup(
    write: suspend () -> Unit,
    validate: suspend () -> Unit,
    cleanup: () -> Unit,
    retain: suspend () -> Unit,
) {
    try {
        currentCoroutineContext().ensureActive()
        write()
        currentCoroutineContext().ensureActive()
        validate()
        currentCoroutineContext().ensureActive()
    } catch (e: Exception) {
        cleanup()
        throw e
    }
    retain()
}
