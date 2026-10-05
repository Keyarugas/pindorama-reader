package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.plus
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
class ResetCategoryFlags(
    private val preferences: LibraryPreferences,
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await(generation: Long = LibraryOperationCoordinator.shared.generation): Boolean {
        val coordinator = LibraryOperationCoordinator.shared
        return coordinator.operation {
            if (generation != coordinator.generation) return@operation false
            val sort = preferences.sortingMode.get()
            categoryRepository.updateAllFlags(sort.type + sort.direction)
            true
        }
    }
}
