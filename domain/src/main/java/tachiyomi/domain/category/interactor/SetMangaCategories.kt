package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

@Inject
class SetMangaCategories(
    private val mangaRepository: MangaRepository,
    private val categoryRepository: CategoryRepository,
) {
    /** The generation belongs to the selection, not to its later confirmation. */
    suspend fun await(expected: Manga, categoryIds: List<Long>, generation: Long): Boolean {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation false
            val current = current(expected) ?: return@operation false
            val available = categoryRepository.getAll().map { it.id }
            if (!available.containsAll(categoryIds)) return@operation false
            replace(current.id, categoryIds.distinct())
        }
    }

    suspend fun awaitAll(
        expected: List<Manga>,
        add: List<Long>,
        remove: List<Long>,
        generation: Long,
    ) {
        val coordinator = LibraryOperationCoordinator.shared
        coordinator.operation {
            if (generation != coordinator.generation) return@operation
            val available = categoryRepository.getAll().map { it.id }
            if (!available.containsAll(add)) return@operation
            expected.distinctBy { it.id }.forEach { manga ->
                val current = current(manga) ?: return@forEach
                val ids = categoryRepository.getCategoriesByMangaId(current.id).map { it.id }
                    .subtract(remove.toSet()).plus(add).toList()
                replace(current.id, ids)
            }
        }
    }

    /** Read the dialog's targets and relations in the same admitted interval. */
    suspend fun loadSelection(expected: List<Manga>, generation: Long): Selection? {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation null
            val mangas = expected.distinctBy { it.id }.mapNotNull { current(it) }
            if (mangas.isEmpty()) return@operation null
            Selection(
                mangas,
                categoryRepository.getAll().filterNot { it.isSystemCategory },
                mangas.associate { manga ->
                    manga.id to categoryRepository.getCategoriesByMangaId(manga.id).map { it.id }
                },
                generation,
            )
        }
    }

    // Only called under the logical intention's participation; the repository owns its transaction.
    private suspend fun replace(mangaId: Long, categoryIds: List<Long>): Boolean = try {
        mangaRepository.setMangaCategories(mangaId, categoryIds)
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        logcat(LogPriority.ERROR) { "CATEGORY_ASSIGNMENT_FAILED" }
        false
    }

    private suspend fun current(expected: Manga): Manga? =
        mangaRepository.getMangaByUrlAndSourceId(expected.url, expected.source)
            ?.takeIf { it.id == expected.id && it.source == expected.source && it.url == expected.url }

    data class Selection(
        val mangas: List<Manga>,
        val categories: List<Category>,
        val categoryIds: Map<Long, List<Long>>,
        val generation: Long,
    )
}
