package eu.kanade.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository

@Inject
class SetBookmarkStatus(
    private val chapterRepository: ChapterRepository,
) {
    suspend fun await(
        bookmarked: Boolean,
        vararg chapters: Chapter,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ) {
        val coordinator = LibraryOperationCoordinator.shared
        coordinator.operation {
            if (generation != coordinator.generation) return@operation
            val updates = chapters.distinctBy { it.id }.mapNotNull { expected ->
                val current = chapterRepository.getChapterById(expected.id)
                    ?: return@mapNotNull null
                if (
                    current.id != expected.id ||
                    current.mangaId != expected.mangaId ||
                    current.url != expected.url ||

                    current.bookmark == bookmarked
                ) {
                    return@mapNotNull null
                }
                ChapterUpdate(id = current.id, bookmark = bookmarked)
            }
            if (updates.isNotEmpty()) chapterRepository.updateAll(updates)
        }
    }
}
