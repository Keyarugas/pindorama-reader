package eu.kanade.tachiyomi.data.backup.restore

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.create.protectedBackupOperation
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Sequential and fail-fast. Completed entry transactions may remain after cancellation/failure. */
@Inject
class ProtectedBackupRestorer(
    private val categories: CategoriesRestorer,
    private val manga: MangaRestorer,
    private val preferences: ProtectedRestorePreferences,
) {
    internal suspend fun restore(
        prepared: PreparedProtectedRestore,
        options: RestoreOptions,
    ) = protectedBackupOperation {
        val backup = prepared.backup
        ProtectedRestorePreferences.validate(backup.backupPreferences)
        currentCoroutineContext().ensureActive()
        if (options.categories) categories.restoreData(backup.backupCategories)
        if (options.libraryEntries) {
            backup.backupManga.forEach {
                currentCoroutineContext().ensureActive()
                manga.restoreProtected(it, if (options.categories) backup.backupCategories else emptyList())
            }
            currentCoroutineContext().ensureActive()
        }
        currentCoroutineContext().ensureActive()
        if (options.appSettings) preferences.apply(backup.backupPreferences)
        currentCoroutineContext().ensureActive()
        // Source settings and repositories are deliberately preserved locally, regardless of caller options.
        // No scheduling: every preference capable of initiating work is excluded by the allowlist.
    }
}
