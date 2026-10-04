package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.plus
import tachiyomi.domain.library.service.LibraryPreferences
import kotlin.random.Random

@Inject
class SetSortModeForCategory(
    private val preferences: LibraryPreferences,
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await(
        categoryId: Long?,
        type: LibrarySort.Type,
        direction: LibrarySort.Direction,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ) {
        val coordinator = LibraryOperationCoordinator.shared
        coordinator.operation {
            if (generation != coordinator.generation) return@operation
            val category = categoryId?.let { categoryRepository.get(it) ?: return@operation }
            if (type == LibrarySort.Type.Random) preferences.randomSortSeed.set(Random.nextInt())
            if (category != null && preferences.categorizedDisplaySettings.get()) {
                categoryRepository.updateFlags(category.id, category.flags + type + direction)
            } else {
                val categories = categoryRepository.getAll()
                preferences.sortingMode.set(LibrarySort(type, direction))
                val flagsByCategory = categories.associate { it.id to (it.flags + type + direction) }
                categoryRepository.updateFlagsBatch(flagsByCategory)
            }
        }
    }

    suspend fun await(
        category: Category?,
        type: LibrarySort.Type,
        direction: LibrarySort.Direction,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ) {
        await(category?.id, type, direction, generation)
    }
}
