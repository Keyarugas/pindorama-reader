package tachiyomi.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class SetMangaDefaultChapterFlags(
    private val libraryPreferences: LibraryPreferences,
    private val setMangaChapterFlags: SetMangaChapterFlags,
    private val getFavorites: GetFavorites,
    private val mangaRepository: MangaRepository,
) {

    suspend fun await(
        manga: Manga,
        onlyIfNotFavorite: Boolean = false,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ) {
        val coordinator = LibraryOperationCoordinator.shared
        withNonCancellableContext {
            coordinator.operation {
                if (generation != coordinator.generation) return@operation
                val current = mangaRepository.getMangaByUrlAndSourceId(manga.url, manga.source)
                    ?.takeIf { it.id == manga.id && it.source == manga.source && it.url == manga.url }
                    ?: return@operation
                if (onlyIfNotFavorite && current.favorite) return@operation
                applyDefaults(current.id)
            }
        }
    }

    suspend fun awaitAll(generation: Long = LibraryOperationCoordinator.shared.generation) {
        val coordinator = LibraryOperationCoordinator.shared
        withNonCancellableContext {
            coordinator.operation {
                if (generation != coordinator.generation) return@operation
                getFavorites.await().forEach { applyDefaults(it.id) }
            }
        }
    }

    suspend fun awaitSetCurrentAsDefault(
        manga: Manga,
        applyToExisting: Boolean,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ): Boolean {
        val coordinator = LibraryOperationCoordinator.shared
        return withNonCancellableContext {
            coordinator.operation {
                if (generation != coordinator.generation) return@operation false
                val current = mangaRepository.getMangaByUrlAndSourceId(manga.url, manga.source)
                    ?.takeIf { it.id == manga.id && it.source == manga.source && it.url == manga.url }
                    ?: return@operation false
                libraryPreferences.setChapterSettingsDefault(current)
                if (applyToExisting) getFavorites.await().forEach { applyDefaults(it.id) }
                true
            }
        }
    }

    private suspend fun applyDefaults(mangaId: Long) {
        with(libraryPreferences) {
            setMangaChapterFlags.awaitSetAllFlags(
                mangaId = mangaId,
                unreadFilter = filterChapterByRead.get(),
                downloadedFilter = filterChapterByDownloaded.get(),
                bookmarkedFilter = filterChapterByBookmarked.get(),
                sortingMode = sortChapterBySourceOrNumber.get(),
                sortingDirection = sortChapterByAscendingOrDescending.get(),
                displayMode = displayChapterByNameOrNumber.get(),
            )
        }
    }
}
