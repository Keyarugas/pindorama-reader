package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

@Inject
class RenameCategory(
    private val categoryRepository: CategoryRepository,
) {

    suspend fun await(
        categoryId: Long,
        name: String,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ): Result {
        val coordinator = LibraryOperationCoordinator.shared
        return withNonCancellableContext {
            coordinator.operation {
                if (generation != coordinator.generation) return@operation Result.Unchanged
                if (categoryRepository.get(categoryId) == null) return@operation Result.Unchanged
                if (name.isEmpty() || categoryRepository.getAll().any { it.id != categoryId && it.name == name }) {
                    return@operation Result.Unchanged
                }
                try {
                    categoryRepository.updateName(categoryId = categoryId, name = name)
                    Result.Success
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logcat(LogPriority.ERROR, e)
                    Result.InternalError(e)
                }
            }
        }
    }

    suspend fun await(
        category: Category,
        name: String,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ) = await(category.id, name, generation)

    sealed interface Result {
        data object Unchanged : Result
        data object Success : Result
        data class InternalError(val error: Throwable) : Result
    }
}
