package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class UpdateMangaNotes(
    private val mangaRepository: MangaRepository,
) {

    suspend fun loadEditor(expected: Manga, generation: Long): Manga? {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation null
            current(expected)
        }
    }

    suspend operator fun invoke(expected: Manga, notes: String, generation: Long): Boolean {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation false
            val current = current(expected) ?: return@operation false
            mangaRepository.update(MangaUpdate(id = current.id, notes = notes))
        }
    }

    private suspend fun current(expected: Manga): Manga? =
        mangaRepository.getMangaByUrlAndSourceId(expected.url, expected.source)
            ?.takeIf { it.id == expected.id && it.source == expected.source && it.url == expected.url }
}
