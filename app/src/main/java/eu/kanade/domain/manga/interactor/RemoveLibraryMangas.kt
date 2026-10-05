package eu.kanade.domain.manga.interactor

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.removeCovers
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/** Library selection removal; category assignment is a separate intention. */
@Inject
class RemoveLibraryMangas(
    private val mangaRepository: MangaRepository,
    private val coverCache: CoverCache,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
) {
    suspend fun await(
        expected: List<Manga>,
        deleteFromLibrary: Boolean,
        deleteChapters: Boolean,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ) {
        val coordinator = LibraryOperationCoordinator.shared
        coordinator.operation {
            if (generation != coordinator.generation) return@operation
            val current = expected.mapNotNull { snapshot ->
                mangaRepository.getMangaByUrlAndSourceId(snapshot.url, snapshot.source)
                    ?.takeIf { it.id == snapshot.id && it.source == snapshot.source && it.url == snapshot.url }
            }
            if (current.isEmpty()) return@operation
            if (deleteFromLibrary) {
                val updates = current.map {
                    it.removeCovers(coverCache)
                    MangaUpdate(id = it.id, favorite = false)
                }
                mangaRepository.updateAll(updates)
            }
            if (deleteChapters) {
                current.forEach { manga ->
                    val source = sourceManager.get(manga.source) as? HttpSource
                    if (source != null) downloadManager.deleteMangaAndWait(manga, source)
                }
            }
        }
    }
}
