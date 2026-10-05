package eu.kanade.domain.track.interactor

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.tachiyomi.data.track.Tracker
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.interactor.GetTracks

/** Preparation is admitted as one intent; a snackbar retains only this age and validated targets. */
@Inject
class TrackReadChapters(
    private val mangas: MangaRepository,
    private val chapters: ChapterRepository,
    private val setReadStatus: SetReadStatus,
    private val refreshTracks: RefreshTracks,
    private val getTracks: GetTracks,
    private val trackChapter: TrackChapter,
) {
    suspend fun prepare(
        expected: Manga,
        selected: List<Chapter>,
        read: Boolean,
        offerTracking: Boolean,
        generation: Long,
    ): Prepared? = withNonCancellableContext {
        val coordinator = LibraryOperationCoordinator.shared
        coordinator.operation {
            if (generation != coordinator.generation) return@operation null
            val manga = currentManga(expected) ?: return@operation null
            val current = currentChapters(manga, selected)
            if (current.isEmpty()) return@operation null
            val result = setReadStatus.await(read, *current.toTypedArray(), generation = generation)
            if (!read || !offerTracking || result is SetReadStatus.Result.InternalError) return@operation null
            val marked = currentChapters(manga, current).filter { it.read }
            if (marked.isEmpty()) return@operation null
            val failures = refreshTracks.await(manga.id, generation)
            val tracks = getTracks.await(manga.id)
            val progress = marked.maxOf { it.chapterNumber }
            Prepared(
                Intent(
                    manga.id,
                    manga.source,
                    manga.url,
                    marked.associate { it.id to it.url },
                    tracks.map { Association(it.id, it.trackerId, it.remoteId, it.libraryId, it.remoteUrl) },
                    generation,
                ),
                progress,
                tracks.any { progress > it.lastChapterRead },
                failures,
            )
        }
    }

    suspend fun confirm(context: Context, intent: Intent): Boolean = withNonCancellableContext {
        val coordinator = LibraryOperationCoordinator.shared
        coordinator.operation {
            if (intent.generation != coordinator.generation) return@operation false
            val manga = mangas.getMangaByUrlAndSourceId(intent.mangaUrl, intent.sourceId)
                ?.takeIf { it.id == intent.mangaId } ?: return@operation false
            val current = intent.chapters.mapNotNull { (id, url) ->
                chapters.getChapterById(id)?.takeIf { it.mangaId == manga.id && it.url == url && it.read }
            }
            val progress = current.maxOfOrNull { it.chapterNumber }
                ?: return@operation false
            val tracks = getTracks.await(manga.id).filter { current ->
                intent.tracks.any {
                    current.id == it.id && current.trackerId == it.trackerId && current.remoteId == it.remoteId &&
                        current.libraryId == it.libraryId && current.remoteUrl == it.remoteUrl
                }
            }
            if (tracks.none { progress > it.lastChapterRead }) return@operation false
            trackChapter.await(context, manga.id, progress, generation = intent.generation, expectedTracks = tracks)
            true
        }
    }

    private suspend fun currentManga(expected: Manga): Manga? =
        mangas.getMangaByUrlAndSourceId(expected.url, expected.source)?.takeIf { it.id == expected.id }

    private suspend fun currentChapters(manga: Manga, selected: List<Chapter>): List<Chapter> =
        selected.distinctBy { it.id }.mapNotNull { expected ->
            chapters.getChapterById(expected.id)?.takeIf {
                it.mangaId == manga.id && it.mangaId == expected.mangaId && it.url == expected.url
            }
        }

    data class Prepared(
        val intent: Intent,
        val chapterNumber: Double,
        val needsUpdate: Boolean,
        val failures: List<Pair<Tracker?, Throwable>>,
    )

    data class Intent(
        val mangaId: Long,
        val sourceId: Long,
        val mangaUrl: String,
        val chapters: Map<Long, String>,
        val tracks: List<Association>,
        val generation: Long,
    )

    data class Association(
        val id: Long,
        val trackerId: Long,
        val remoteId: Long,
        val libraryId: Long?,
        val remoteUrl: String,
    )
}
