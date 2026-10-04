package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.util.removeCovers
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Clock

/** Browse membership intention; category assignment remains a separate operation. */
@Inject
class ToggleMangaFavorite(
    private val mangaRepository: MangaRepository,
    private val defaults: SetMangaDefaultChapterFlags,
    private val addTracks: AddTracks,
    private val sourceManager: SourceManager,
    private val coverCache: CoverCache,
) {
    suspend fun await(
        expected: Manga,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ) {
        val coordinator = LibraryOperationCoordinator.shared
        coordinator.operation {
            if (generation != coordinator.generation) return@operation
            val current = mangaRepository.getMangaByUrlAndSourceId(expected.url, expected.source)
                ?.takeIf { it.id == expected.id && it.source == expected.source && it.url == expected.url }
                ?: return@operation
            val favorite = !current.favorite
            if (favorite) {
                defaults.await(current, onlyIfNotFavorite = true)
                addTracks.bindEnhancedTrackers(current, sourceManager.getOrStub(current.source))
            } else {
                val withoutCovers = current.removeCovers(coverCache)
                if (withoutCovers.coverLastModified != current.coverLastModified) {
                    mangaRepository.update(
                        MangaUpdate(id = current.id, coverLastModified = withoutCovers.coverLastModified),
                    )
                }
            }
            mangaRepository.update(
                MangaUpdate(
                    id = current.id,
                    favorite = favorite,
                    dateAdded = if (favorite) Clock.System.now().toEpochMilliseconds() else 0,
                ),
            )
        }
    }
}
