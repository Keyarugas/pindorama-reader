package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class ResetViewerFlags(
    private val mangaRepository: MangaRepository,
) {

    /** Null means the intention expired, so the caller must not announce an outcome. */
    suspend fun await(generation: Long = LibraryOperationCoordinator.shared.generation): Boolean? {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation null
            mangaRepository.resetViewerFlags()
        }
    }
}
