package mihon.domain.migration.usecases

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import kotlin.time.Clock

@Inject
class MigrateMangaUseCase(
    private val sourcePreferences: SourcePreferences,
    private val getManga: GetManga,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val updateManga: UpdateManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val getCategories: GetCategories,
    private val setMangaCategories: SetMangaCategories,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    private val coverCache: CoverCache,
    private val updateMangaFromRemote: UpdateMangaFromRemote,
) {
    private val enhancedServices by lazy { trackerManager.trackers.filterIsInstance<EnhancedTracker>() }

    suspend operator fun invoke(
        current: Manga,
        target: Manga,
        replace: Boolean,
        generation: Long,
        flags: Set<MigrationFlag>? = null,
    ): Boolean = LibraryOperationCoordinator.shared.operation {
        if (generation != LibraryOperationCoordinator.shared.generation) return@operation false
        val freshCurrent = getManga.await(current.id)
        currentCoroutineContext().ensureActive()
        val freshTarget = getManga.await(target.id)
        currentCoroutineContext().ensureActive()
        if (freshCurrent == null || freshCurrent.id != current.id || freshCurrent.source != current.source ||
            freshCurrent.url != current.url
        ) {
            return@operation false
        }
        if (freshTarget == null || freshTarget.id != target.id || freshTarget.source != target.source ||
            freshTarget.url != target.url
        ) {
            return@operation false
        }
        migrate(freshCurrent, freshTarget, replace, generation, flags ?: sourcePreferences.migrationFlags.get())
    }

    /** A retained manual selection is applied under one lease, including progress between items. */
    suspend fun awaitAll(
        selection: List<Selection>,
        replace: Boolean,
        generation: Long,
        flags: Set<MigrationFlag>,
        onProgress: suspend (Int, Boolean) -> Unit = { _, _ -> },
    ): Boolean = LibraryOperationCoordinator.shared.operation {
        if (generation != LibraryOperationCoordinator.shared.generation) return@operation false
        var completed = true
        selection.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            val applied = try {
                item.target?.let { invoke(item.current, it, replace, generation, flags) } ?: true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            completed = completed && applied
            onProgress(index, applied)
        }
        completed
    }

    data class Selection(val current: Manga, val target: Manga?)

    private suspend fun migrate(
        current: Manga,
        target: Manga,
        replace: Boolean,
        generation: Long,
        flags: Set<MigrationFlag>,
    ): Boolean {
        val targetSource = sourceManager.get(target.source) ?: return false
        val currentSource = sourceManager.get(current.source)

        try {
            updateMangaFromRemote(target, fetchChapters = true).getOrThrow()

            // Update chapters read, bookmark and dateFetch
            if (MigrationFlag.CHAPTER in flags) {
                val prevMangaChapters = getChaptersByMangaId.await(current.id)
                val mangaChapters = getChaptersByMangaId.await(target.id)

                val maxChapterRead = prevMangaChapters
                    .filter { it.read }
                    .maxOfOrNull { it.chapterNumber }

                val updatedMangaChapters = mangaChapters.map { mangaChapter ->
                    var updatedChapter = mangaChapter
                    if (updatedChapter.isRecognizedNumber) {
                        val prevChapter = prevMangaChapters
                            .find { it.isRecognizedNumber && it.chapterNumber == updatedChapter.chapterNumber }

                        if (prevChapter != null) {
                            updatedChapter = updatedChapter.copy(
                                dateFetch = prevChapter.dateFetch,
                                bookmark = prevChapter.bookmark,
                            )
                        }

                        if (maxChapterRead != null && updatedChapter.chapterNumber <= maxChapterRead) {
                            updatedChapter = updatedChapter.copy(read = true)
                        }
                    }

                    updatedChapter
                }

                val chapterUpdates = updatedMangaChapters.map { it.toChapterUpdate() }
                updateChapter.awaitAll(chapterUpdates)
            }

            // Update categories
            if (MigrationFlag.CATEGORY in flags) {
                val categoryIds = getCategories.await(current.id).map { it.id }
                // Retain the age of the outer manual intent through this nested mutation.
                setMangaCategories.await(target, categoryIds, generation)
            }

            // Update track
            getTracks.await(current.id).mapNotNull { track ->
                val updatedTrack = track.copy(mangaId = target.id)

                val service = enhancedServices
                    .firstOrNull { it.isTrackFrom(updatedTrack, current, currentSource) }

                if (service != null) {
                    service.migrateTrack(updatedTrack, target, targetSource)
                } else {
                    updatedTrack
                }
            }
                .takeIf { it.isNotEmpty() }
                ?.let { insertTrack.awaitAll(it) }

            // Delete downloaded
            if (MigrationFlag.REMOVE_DOWNLOAD in flags && currentSource != null) {
                downloadManager.deleteMangaAndWait(current, currentSource)
            }

            // Update custom cover (recheck if custom cover exists)
            if (MigrationFlag.CUSTOM_COVER in flags && current.hasCustomCover()) {
                coverCache.setCustomCoverToCache(target, coverCache.getCustomCoverFile(current.id).inputStream())
            }

            val currentMangaUpdate = MangaUpdate(
                id = current.id,
                favorite = false,
                dateAdded = 0,
            )
                .takeIf { replace }
            val targetMangaUpdate = MangaUpdate(
                id = target.id,
                favorite = true,
                isPrivate = current.isPrivate || target.isPrivate,
                chapterFlags = current.chapterFlags,
                viewerFlags = current.viewerFlags,
                dateAdded = if (replace) current.dateAdded else Clock.System.now().toEpochMilliseconds(),
                notes = if (MigrationFlag.NOTES in flags) current.notes else null,
            )

            return updateManga.awaitAll(listOfNotNull(currentMangaUpdate, targetMangaUpdate))
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return false
        }
    }
}
