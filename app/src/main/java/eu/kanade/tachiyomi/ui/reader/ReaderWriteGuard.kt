package eu.kanade.tachiyomi.ui.reader

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

/** Narrow reader intents, never a write-back of the retained ReaderChapter/Manga snapshot. */
@Inject
class ReaderWriteGuard(
    private val database: Database,
    private val mangas: MangaRepository,
    private val chapters: ChapterRepository,
) {
    private val coordinator = LibraryOperationCoordinator.shared

    class StaleState : Exception("READER_STATE_CHANGED")

    private suspend fun currentManga(generation: Long, expected: Manga): Manga {
        if (generation != coordinator.generation) throw StaleState()
        return mangas.getMangaByUrlAndSourceId(expected.url, expected.source)
            ?.takeIf { it.id == expected.id } ?: throw StaleState()
    }

    private suspend fun currentChapter(expectedManga: Manga, expected: Chapter): Chapter =
        chapters.getChapterById(expected.id)?.takeIf {
            it.mangaId == expectedManga.id && expected.mangaId == expectedManga.id && it.url == expected.url
        } ?: throw StaleState()

    private suspend fun <T> chapterWrite(
        generation: Long,
        manga: Manga,
        chapter: Chapter,
        block: suspend (Chapter) -> T,
    ): T = coordinator.operation {
        // Admission precedes the transaction; identity, current state and write share the transaction.
        database.transactionWithResult {
            currentManga(generation, manga)
            block(currentChapter(manga, chapter)).also { currentCoroutineContext().ensureActive() }
        }
    }

    suspend fun progress(
        generation: Long,
        manga: Manga,
        chapter: Chapter,
        page: Int,
        completed: Boolean,
        markDuplicates: Boolean,
    ): Chapter = chapterWrite(generation, manga, chapter) { current ->
        require(page >= 0)
        val read = current.read || completed
        chapters.update(ChapterUpdate(id = current.id, read = read, lastPageRead = page.toLong()))
        if (completed && markDuplicates && current.isRecognizedNumber) {
            val duplicates = chapters.getChapterByMangaId(manga.id).filter {
                !it.read && it.isRecognizedNumber && it.chapterNumber.toFloat() == current.chapterNumber.toFloat()
            }
            chapters.updateAll(duplicates.map { ChapterUpdate(id = it.id, read = true) })
        }
        current.copy(read = read, lastPageRead = page.toLong())
    }

    suspend fun bookmark(generation: Long, manga: Manga, chapter: Chapter): Boolean =
        chapterWrite(generation, manga, chapter) { current ->
            (!current.bookmark).also { chapters.update(ChapterUpdate(id = current.id, bookmark = it)) }
        }

    suspend fun history(generation: Long, manga: Manga, chapter: Chapter, update: HistoryUpdate) =
        chapterWrite(generation, manga, chapter) { current ->
            check(update.chapterId == current.id)
            // The legacy HistoryRepository catches SQL errors. This path must not acknowledge them as saved.
            database.historyQueries.upsert(update.chapterId, update.readAt, update.sessionReadDuration)
        }

    suspend fun viewerFlag(generation: Long, manga: Manga, flag: Long, mask: Long) = coordinator.operation {
        database.transaction {
            val current = currentManga(generation, manga)
            check(
                mangas.update(
                    MangaUpdate(
                        id = current.id,
                        viewerFlags =
                        current.viewerFlags and mask.inv() or (flag and mask),
                    ),
                ),
            ) {
                "READER_WRITE_FAILED"
            }
            currentCoroutineContext().ensureActive()
        }
    }

    /** External effects participate until completion, without holding a SQLite transaction. */
    suspend fun <T> mangaEffect(generation: Long, manga: Manga, block: suspend (Manga) -> T): T =
        coordinator.operation { block(currentManga(generation, manga)) }

    suspend fun <T> chapterEffect(
        generation: Long,
        manga: Manga,
        chapter: Chapter,
        block: suspend (Manga, Chapter) -> T,
    ): T = coordinator.operation {
        val current = currentManga(generation, manga)
        block(current, currentChapter(current, chapter))
    }
}
