package eu.kanade.tachiyomi.data.backup.create

import eu.kanade.tachiyomi.data.backup.BackupError
import eu.kanade.tachiyomi.data.backup.BackupException
import tachiyomi.domain.manga.model.Manga

/** Applied to the actual execution snapshot, regardless of job type or private-session state. */
internal object ConventionalBackupPolicy {
    fun check(mangas: List<Manga>, options: BackupOptions) {
        if (options.libraryEntries && mangas.any { it.isPrivate }) {
            throw BackupException(BackupError.PROTECTION_REQUIRED)
        }
    }
}
