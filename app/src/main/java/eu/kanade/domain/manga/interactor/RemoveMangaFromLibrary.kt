package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.util.removeCovers
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/** Explicit removal: never toggles an already removed target back into the library. */
@Inject
class RemoveMangaFromLibrary(
    private val mangaRepository: MangaRepository,
    private val coverCache: CoverCache,
) {
    suspend fun await(expected: Manga, generation: Long): Manga? {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation null
            val current = mangaRepository.getMangaByUrlAndSourceId(expected.url, expected.source)
                ?.takeIf { it.id == expected.id && it.source == expected.source && it.url == expected.url }
                ?: return@operation null
            if (!current.favorite) return@operation null
            if (!mangaRepository.update(MangaUpdate(current.id, favorite = false, dateAdded = 0))) {
                return@operation null
            }
            val withoutCovers = current.removeCovers(coverCache)
            if (withoutCovers.coverLastModified != current.coverLastModified) {
                mangaRepository.update(MangaUpdate(current.id, coverLastModified = withoutCovers.coverLastModified))
            }
            current
        }
    }
}
