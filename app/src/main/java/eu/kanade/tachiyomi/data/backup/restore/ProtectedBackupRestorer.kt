package eu.kanade.tachiyomi.data.backup.restore

import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.backup.BackupError
import eu.kanade.tachiyomi.data.backup.BackupException
import eu.kanade.tachiyomi.data.backup.create.protectedBackupOperation
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database

/** One database transaction; preferences remain a separate, post-commit phase. */
@Inject
class ProtectedBackupRestorer(
    private val categories: CategoriesRestorer,
    private val manga: MangaRestorer,
    private val preferences: ProtectedRestorePreferences,
    private val database: Database,
    private val delayedTrackingStore: DelayedTrackingStore,
) {
    /** Explicit, separate import. Never schedules tasks or changes security preferences. */
    internal suspend fun importVisualPreferences(prepared: PreparedProtectedRestore) = protectedBackupOperation {
        preferences.apply(prepared.backup.backupPreferences)
    }

    internal suspend fun restore(
        prepared: PreparedProtectedRestore,
        options: RestoreOptions,
    ) = protectedBackupOperation {
        val backup = prepared.backup
        currentCoroutineContext().ensureActive()
        if (options.categories || options.libraryEntries) {
            try {
                LibraryOperationCoordinator.shared.exclusive {
                    ProtectedRestorePreferences.validate(backup.backupPreferences)
                    currentCoroutineContext().ensureActive()
                    if (delayedTrackingStore.getItems().isNotEmpty()) {
                        throw BackupException(BackupError.PENDING_TRACKING)
                    }
                    database.transaction {
                        if (options.categories) categories.restoreData(backup.backupCategories)
                        if (options.libraryEntries) {
                            backup.backupManga.forEach {
                                currentCoroutineContext().ensureActive()
                                manga.restoreProtected(
                                    it,
                                    if (options.categories) backup.backupCategories else emptyList(),
                                )
                            }
                            currentCoroutineContext().ensureActive()
                        }
                        currentCoroutineContext().ensureActive()
                    }
                }
            } catch (_: LibraryOperationCoordinator.Unavailable) {
                throw BackupException(BackupError.BUSY)
            }
        } else {
            ProtectedRestorePreferences.validate(backup.backupPreferences)
        }
        currentCoroutineContext().ensureActive()
        if (options.appSettings) preferences.apply(backup.backupPreferences)
        currentCoroutineContext().ensureActive()
        // Source settings and repositories are deliberately preserved locally, regardless of caller options.
        // No scheduling: every preference capable of initiating work is excluded by the allowlist.
    }
}
