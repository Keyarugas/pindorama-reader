package eu.kanade.domain.track.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.lang.convertEpochMillisZone
import kotlinx.datetime.TimeZone
import logcat.LogPriority
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.interactor.InsertTrack

@Inject
class AddTracks(
    private val insertTrack: InsertTrack,
    private val syncChapterProgressWithTrack: SyncChapterProgressWithTrack,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val trackerManager: TrackerManager,
    private val getHistory: GetHistory,
    private val mangaRepository: MangaRepository,
) {

    // TODO: update all trackers based on common data
    suspend fun bind(tracker: Tracker, item: Track, mangaId: Long) {
        val coordinator = LibraryOperationCoordinator.shared
        val generation = coordinator.generation
        withNonCancellableContext {
            coordinator.operation {
                if (generation != coordinator.generation) return@operation
                withIOContext {
                    val allChapters = getChaptersByMangaId.await(mangaId)
                    val hasReadChapters = allChapters.any { it.read }
                    tracker.bind(item, hasReadChapters)

                    var track = item.toDomainTrack(idRequired = false) ?: return@withIOContext

                    insertTrack.await(track)

                    // TODO: merge into [SyncChapterProgressWithTrack]?
                    // Update chapter progress if newer chapters marked read locally
                    if (hasReadChapters) {
                        val latestLocalReadChapterNumber = allChapters
                            .sortedBy { it.chapterNumber }
                            .takeWhile { it.read }
                            .lastOrNull()
                            ?.chapterNumber ?: -1.0

                        if (latestLocalReadChapterNumber > track.lastChapterRead) {
                            track = track.copy(
                                lastChapterRead = latestLocalReadChapterNumber,
                            )
                            tracker.setRemoteLastChapterRead(track.toDbTrack(), latestLocalReadChapterNumber.toInt())
                        }

                        if (track.startDate <= 0) {
                            val firstReadChapterDate = getHistory.await(mangaId)
                                .sortedBy { it.readAt }
                                .firstOrNull()
                                ?.readAt

                            firstReadChapterDate?.let {
                                val startDate = firstReadChapterDate.time.convertEpochMillisZone(
                                    TimeZone.currentSystemDefault(),
                                    TimeZone.UTC,
                                )
                                track = track.copy(
                                    startDate = startDate,
                                )
                                tracker.setRemoteStartDate(track.toDbTrack(), startDate)
                            }
                        }
                    }

                    syncChapterProgressWithTrack.await(mangaId, track, tracker)
                }
            }
        }
    }

    suspend fun bindEnhancedTrackers(manga: Manga, source: Source) {
        val coordinator = LibraryOperationCoordinator.shared
        val generation = coordinator.generation
        withNonCancellableContext {
            coordinator.operation {
                if (generation != coordinator.generation) return@operation
                withIOContext {
                    // Nullable identity lookup also rejects a replaced local ID without masking cancellation.
                    val currentManga = mangaRepository.getMangaByUrlAndSourceId(manga.url, manga.source)
                        ?: return@withIOContext
                    if (currentManga.id != manga.id ||
                        currentManga.source != manga.source ||
                        currentManga.url != manga.url
                    ) {
                        return@withIOContext
                    }
                    trackerManager.loggedInTrackers()
                        .filterIsInstance<EnhancedTracker>()
                        .filter { it.accept(source) }
                        .forEach { service ->
                            try {
                                service.match(currentManga)?.let { track ->
                                    track.manga_id = currentManga.id
                                    (service as Tracker).bind(track)
                                    insertTrack.await(track.toDomainTrack(idRequired = false)!!)

                                    syncChapterProgressWithTrack.await(
                                        currentManga.id,
                                        track.toDomainTrack(idRequired = false)!!,
                                        service,
                                    )
                                }
                            } catch (e: Exception) {
                                logcat(
                                    LogPriority.WARN,
                                    e,
                                ) { "Could not match mangaId=${manga.id} with service $service" }
                            }
                        }
                }
            }
        }
    }
}
