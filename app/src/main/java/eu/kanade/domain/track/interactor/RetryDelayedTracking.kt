package eu.kanade.domain.track.interactor

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.store.DelayedTrackingStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.track.repository.TrackRepository

@Inject
class RetryDelayedTracking(
    private val store: DelayedTrackingStore,
    private val tracks: TrackRepository,
    private val trackChapter: TrackChapter,
) {
    suspend fun await(context: Context, generation: Long): Boolean {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation false
            val pending = store.getItems().mapNotNull { item ->
                val track = tracks.getTrackById(item.trackId)
                currentCoroutineContext().ensureActive()
                if (track == null) store.remove(item.trackId)
                track?.let { it to item.lastChapterRead.toDouble() }
            }
            pending.forEach { (track, progress) ->
                trackChapter.await(
                    context,
                    track.mangaId,
                    progress,
                    setupJobOnFailure = false,
                    generation = generation,
                    expectedTracks = listOf(track),
                )
            }
            store.getItems().isEmpty()
        }
    }
}
