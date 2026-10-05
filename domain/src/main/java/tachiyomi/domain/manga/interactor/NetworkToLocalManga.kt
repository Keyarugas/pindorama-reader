package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class NetworkToLocalManga(
    private val mangaRepository: MangaRepository,
) {

    /** The caller must capture generation before the request that produced this response. */
    suspend operator fun invoke(manga: Manga, generation: Long): Manga? {
        return invoke(listOf(manga), generation)?.single()
    }

    suspend operator fun invoke(manga: List<Manga>, generation: Long): List<Manga>? {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation null
            val current = manga.map { mangaRepository.getMangaByUrlAndSourceId(it.url, it.source) }
            val toApply = manga.filterIndexed { index, _ -> current[index]?.favorite != true }
            currentCoroutineContext().ensureActive()
            val applied = if (toApply.isEmpty()) {
                emptyList()
            } else {
                mangaRepository.insertNetworkManga(toApply)
            }.iterator()
            current.map { existing -> existing?.takeIf { it.favorite } ?: applied.next() }
        }
    }
}
