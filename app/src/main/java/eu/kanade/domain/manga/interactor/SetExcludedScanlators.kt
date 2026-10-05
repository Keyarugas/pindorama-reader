package eu.kanade.domain.manga.interactor

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class SetExcludedScanlators(
    private val database: Database,
    private val mangaRepository: MangaRepository,
) {

    suspend fun loadSelection(expected: Manga, generation: Long): Set<String>? {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation null
            val current = current(expected) ?: return@operation null
            database.excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(current.id).awaitAsList().toSet()
        }
    }

    suspend fun await(expected: Manga, excludedScanlators: Set<String>, generation: Long): Boolean {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation false
            val current = current(expected) ?: return@operation false
            val mangaId = current.id
            database.transaction {
                val currentExcluded = database.excluded_scanlatorsQueries
                    .getExcludedScanlatorsByMangaId(mangaId)
                    .awaitAsList()
                    .toSet()
                val toAdd = excludedScanlators.minus(currentExcluded)
                for (scanlator in toAdd) {
                    database.excluded_scanlatorsQueries.insert(mangaId, scanlator)
                }
                val toRemove = currentExcluded.minus(excludedScanlators)
                database.excluded_scanlatorsQueries.remove(mangaId, toRemove)
            }
            true
        }
    }

    private suspend fun current(expected: Manga): Manga? =
        mangaRepository.getMangaByUrlAndSourceId(expected.url, expected.source)
            ?.takeIf { it.id == expected.id && it.source == expected.source && it.url == expected.url }
}
