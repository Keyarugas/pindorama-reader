package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class RemoveHistory(
    private val repository: HistoryRepository,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
    private val getManga: GetManga,
) {

    /** Resolve canonical identities before presenting the destructive confirmation. */
    suspend fun loadTarget(history: HistoryWithRelations, generation: Long): Target? {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation null
            val chapter = chapterRepository.getChapterById(history.chapterId) ?: return@operation null
            if (chapter.mangaId != history.mangaId || !hasHistory(history.id, chapter)) return@operation null
            val manga = getManga.await(history.mangaId) ?: return@operation null
            if (manga.id != history.mangaId || manga.source != history.coverData.sourceId) return@operation null
            Target(history.id, manga, chapter, generation)
        }
    }

    suspend fun awaitAll(generation: Long): Boolean {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation false
            repository.deleteAllHistory()
        }
    }

    suspend fun await(target: Target): Boolean {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (target.generation != coordinator.generation) return@operation false
            if (currentManga(target.manga) == null) return@operation false
            val chapter = chapterRepository.getChapterById(target.chapter.id) ?: return@operation false
            if (chapter.id != target.chapter.id || chapter.mangaId != target.manga.id ||
                chapter.mangaId != target.chapter.mangaId ||
                chapter.url != target.chapter.url || !hasHistory(target.historyId, chapter)
            ) {
                return@operation false
            }
            repository.resetHistory(target.historyId)
            true
        }
    }

    suspend fun await(manga: Manga, generation: Long): Boolean {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation false
            val current = currentManga(manga) ?: return@operation false
            repository.resetHistoryByMangaId(current.id)
            true
        }
    }

    private suspend fun currentManga(expected: Manga): Manga? =
        mangaRepository.getMangaByUrlAndSourceId(expected.url, expected.source)
            ?.takeIf { it.id == expected.id && it.source == expected.source && it.url == expected.url }

    private suspend fun hasHistory(id: Long, chapter: Chapter): Boolean =
        repository.getHistoryByMangaId(chapter.mangaId).any { it.id == id && it.chapterId == chapter.id }

    data class Target(val historyId: Long, val manga: Manga, val chapter: Chapter, val generation: Long)
}
