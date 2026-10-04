package tachiyomi.domain.category.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

@Inject
class ReorderCategory(
    private val categoryRepository: CategoryRepository,
) {
    private val mutex = Mutex()

    suspend fun await(
        category: Category,
        newIndex: Int,
        generation: Long = LibraryOperationCoordinator.shared.generation,
    ): Result {
        val coordinator = LibraryOperationCoordinator.shared
        return withNonCancellableContext {
            coordinator.operation {
                if (generation != coordinator.generation) return@operation Result.Unchanged
                mutex.withLock {
                    val categories = categoryRepository.getAll()
                        .filterNot(Category::isSystemCategory)
                        .toMutableList()

                    val currentIndex = categories.indexOfFirst { it.id == category.id }
                    if (currentIndex == -1) {
                        return@operation Result.Unchanged
                    }

                    try {
                        categories.add(newIndex.coerceIn(0, categories.lastIndex), categories.removeAt(currentIndex))

                        categoryRepository.updateAllOrders(orderedIds = categories.map { it.id })
                        Result.Success
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.ERROR, e)
                        Result.InternalError(e)
                    }
                }
            }
        }
    }

    sealed interface Result {
        data object Success : Result
        data object Unchanged : Result
        data class InternalError(val error: Throwable) : Result
    }
}
