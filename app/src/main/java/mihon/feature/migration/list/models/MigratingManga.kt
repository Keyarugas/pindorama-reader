package mihon.feature.migration.list.models

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.core.common.util.lang.libraryAsync
import tachiyomi.domain.manga.model.Manga
import kotlin.coroutines.CoroutineContext

class MigratingManga(
    val manga: Manga,
    val chapterCount: Int,
    val latestChapter: Double?,
    val source: String,
    parentContext: CoroutineContext,
    private val coordinator: LibraryOperationCoordinator = LibraryOperationCoordinator.shared,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val migrationScope = CoroutineScope(parentContext + SupervisorJob(parentContext[Job]) + dispatcher)

    fun <T> produce(block: suspend CoroutineScope.() -> T): Deferred<T> =
        migrationScope.libraryAsync(coordinator, block)

    val searchResult = MutableStateFlow<SearchResult>(SearchResult.Searching)

    sealed interface SearchResult {
        data object Searching : SearchResult
        data object NotFound : SearchResult
        data class Success(
            val manga: Manga,
            val chapterCount: Int,
            val latestChapter: Double?,
            val source: String,
        ) : SearchResult
    }
}
