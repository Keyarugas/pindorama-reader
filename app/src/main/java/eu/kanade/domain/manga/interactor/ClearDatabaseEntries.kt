package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.data.Database

/** One confirmed maintenance intention, including its existing history cleanup. */
@Inject
class ClearDatabaseEntries(private val database: Database) {
    suspend fun await(sourceIds: List<Long>, keepReadManga: Boolean, generation: Long): Boolean =
        withNonCancellableContext {
            val coordinator = LibraryOperationCoordinator.shared
            coordinator.operation {
                if (generation != coordinator.generation) return@operation false
                database.mangasQueries.deleteNonLibraryManga(sourceIds, keepReadManga.toLong())
                database.historyQueries.removeResettedHistory()
                true
            }
        }
}
