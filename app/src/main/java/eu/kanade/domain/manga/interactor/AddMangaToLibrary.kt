package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.interactor.AddTracks
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Clock

/** Explicit add and category assignment, including their derived effects, under one lease. */
@Inject
class AddMangaToLibrary(
    private val mangaRepository: MangaRepository,
    private val categories: SetMangaCategories,
    private val defaults: SetMangaDefaultChapterFlags,
    private val addTracks: AddTracks,
    private val sourceManager: SourceManager,
) {
    suspend fun await(
        expected: Manga,
        categoryIds: List<Long>,
        generation: Long,
        applyDefaults: Boolean = false,
    ): Boolean {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation false
            val selection = categories.loadSelection(listOf(expected), generation) ?: return@operation false
            val current = selection.mangas.single()
            if (!selection.categories.map { it.id }.containsAll(categoryIds)) return@operation false
            if (!current.favorite) {
                if (applyDefaults) defaults.await(current, onlyIfNotFavorite = true, generation = generation)
                addTracks.bindEnhancedTrackers(current, sourceManager.getOrStub(current.source))
                if (!mangaRepository.update(
                        MangaUpdate(
                            id = current.id,
                            favorite = true,
                            dateAdded = Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                ) {
                    return@operation false
                }
            }
            categories.await(current, categoryIds, generation)
        }
    }
}
