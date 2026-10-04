package eu.kanade.domain.track.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.model.hasSameAssociation
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import logcat.LogPriority
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.io.Serializable
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/** An editor/removal target retains its original age and both local and remote identities. */
@Inject
class ManualTracking(
    private val getManga: GetManga,
    private val tracks: TrackRepository,
    private val trackerManager: TrackerManager,
    private val deleteTrack: DeleteTrack,
) {
    suspend fun loadTarget(expected: Track, generation: Long): Target? {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation null
            val track = tracks.getTrackById(expected.id)?.takeIf { it.hasSameAssociation(expected) }
                ?: return@operation null
            val manga = getManga.await(track.mangaId)
            currentCoroutineContext().ensureActive()
            if (manga == null) return@operation null
            Target(manga, track, generation)
        }
    }

    suspend fun update(target: Target, edit: suspend (Tracker, DbTrack) -> Unit): Boolean =
        withNonCancellableContext {
            val coordinator = LibraryOperationCoordinator.shared
            coordinator.operation {
                if (target.generation != coordinator.generation) return@operation false
                val current = current(target) ?: return@operation false
                val service = trackerManager.get(current.trackerId) ?: return@operation false
                edit(service, current.toDbTrack())
                true
            }
        }

    suspend fun unlink(target: Target, removeRemote: Boolean): Boolean = withNonCancellableContext {
        val coordinator = LibraryOperationCoordinator.shared
        coordinator.operation {
            if (target.generation != coordinator.generation) return@operation false
            val current = current(target) ?: return@operation false
            val service = trackerManager.get(current.trackerId) ?: return@operation false
            deleteTrack.await(current.mangaId, current.trackerId)
            if (removeRemote && service is DeletableTracker) {
                try {
                    service.delete(current)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    logcat(LogPriority.ERROR) { "TRACKING_REMOVAL_FAILED" }
                }
            }
            true
        }
    }

    private suspend fun current(target: Target): Track? {
        val manga = getManga.await(target.manga.id) ?: return null
        if (manga.source != target.manga.source || manga.url != target.manga.url) return null
        return tracks.getTrackById(target.track.id)?.takeIf { it.hasSameAssociation(target.track) }
    }

    data class Target(val manga: Manga, val track: Track, val generation: Long) : Serializable
}
