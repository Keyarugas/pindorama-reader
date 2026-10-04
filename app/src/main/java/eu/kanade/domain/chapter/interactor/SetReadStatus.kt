package eu.kanade.domain.chapter.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.download.interactor.DeleteDownload
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class SetReadStatus(
    private val downloadPreferences: DownloadPreferences,
    private val deleteDownload: DeleteDownload,
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
) {

    suspend fun await(read: Boolean, vararg chapters: Chapter): Result = apply(read) {
        chapters.distinctBy { it.id }.mapNotNull { expected ->
            chapterRepository.getChapterById(expected.id)?.takeIf {
                it.id == expected.id && it.mangaId == expected.mangaId && it.url == expected.url
            }
        }
    }

    suspend fun await(mangaId: Long, read: Boolean): Result = apply(read) {
        chapterRepository.getChapterByMangaId(mangaId)
    }

    suspend fun await(manga: Manga, read: Boolean): Result = await(manga.id, read)

    suspend fun await(
        read: Boolean,
        mangaId: Long,
        chapterUrls: Array<String>,
    ): Result = apply(read) {
        chapterUrls.distinct().mapNotNull { url ->
            chapterRepository.getChapterByUrlAndMangaId(url, mangaId)?.takeIf {
                it.mangaId == mangaId && it.url == url
            }
        }
    }

    private suspend fun apply(read: Boolean, loadCurrent: suspend () -> List<Chapter>): Result {
        val coordinator = LibraryOperationCoordinator.shared
        val generation = coordinator.generation
        return withNonCancellableContext {
            coordinator.operation {
                if (generation != coordinator.generation) return@operation Result.NoChapters
                val chaptersToUpdate = loadCurrent().filter {
                    if (read) !it.read else it.read || it.lastPageRead > 0
                }
                if (chaptersToUpdate.isEmpty()) return@operation Result.NoChapters

                try {
                    chapterRepository.updateAll(
                        chaptersToUpdate.map {
                            ChapterUpdate(
                                id = it.id,
                                read = read,
                                lastPageRead = if (read) null else 0,
                            )
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e)
                    return@operation Result.InternalError(e)
                }

                if (read && downloadPreferences.removeAfterMarkedAsRead.get()) {
                    chaptersToUpdate.groupBy { it.mangaId }.forEach { (mangaId, chapters) ->
                        deleteDownload.awaitAll(
                            manga = mangaRepository.getMangaById(mangaId),
                            chapters = chapters.toTypedArray(),
                        )
                    }
                }
                Result.Success
            }
        }
    }

    sealed interface Result {
        data object Success : Result
        data object NoChapters : Result
        data class InternalError(val error: Throwable) : Result
    }
}
