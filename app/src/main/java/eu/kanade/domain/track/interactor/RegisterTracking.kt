package eu.kanade.domain.track.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.model.hasSameAssociation
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository
import java.io.Serializable
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/** Network results keep the session token; network waiting itself owns no library lease. */
@Inject
class RegisterTracking(
    private val getManga: GetManga,
    private val tracks: TrackRepository,
    private val trackerManager: TrackerManager,
    private val addTracks: AddTracks,
) {
    suspend fun loadSession(mangaId: Long, sourceId: Long, trackerId: Long, generation: Long): Session? {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation null
            val manga = getManga.await(mangaId)
            currentCoroutineContext().ensureActive()
            if (manga == null || manga.source != sourceId) return@operation null
            Session(manga, trackerId, tracks.getTracksByMangaId(mangaId).find { it.trackerId == trackerId }, generation)
        }
    }

    suspend fun search(session: Session, query: String): List<TrackSearch>? {
        val coordinator = LibraryOperationCoordinator.shared
        if (session.generation != coordinator.generation) return null
        val results = trackerManager.get(session.trackerId)?.search(query) ?: return emptyList()
        return results.takeIf { session.generation == coordinator.generation }
    }

    suspend fun matchAndBind(session: Session): Boolean? = withNonCancellableContext {
        if (session.generation != LibraryOperationCoordinator.shared.generation) return@withNonCancellableContext null
        val service =
            trackerManager.get(session.trackerId) as? EnhancedTracker ?: return@withNonCancellableContext false
        val result = service.match(session.manga)
        if (session.generation != LibraryOperationCoordinator.shared.generation) return@withNonCancellableContext null
        if (result == null) return@withNonCancellableContext false
        if (bind(session, result)) true else null
    }

    suspend fun bind(session: Session, item: DbTrack): Boolean = withNonCancellableContext {
        val coordinator = LibraryOperationCoordinator.shared
        coordinator.operation {
            if (session.generation != coordinator.generation) return@operation false
            val manga = getManga.await(session.manga.id) ?: return@operation false
            if (manga.source != session.manga.source || manga.url != session.manga.url) return@operation false
            val current = tracks.getTracksByMangaId(manga.id).find { it.trackerId == session.trackerId }
            if ((session.track == null && current != null) ||
                (session.track != null && (current == null || !current.hasSameAssociation(session.track)))
            ) {
                return@operation false
            }
            if (item.tracker_id != session.trackerId) return@operation false
            val service = trackerManager.get(session.trackerId) ?: return@operation false
            item.manga_id = manga.id
            addTracks.bind(service, item, manga.id, session.generation)
            true
        }
    }

    data class Session(val manga: Manga, val trackerId: Long, val track: Track?, val generation: Long) : Serializable
}
