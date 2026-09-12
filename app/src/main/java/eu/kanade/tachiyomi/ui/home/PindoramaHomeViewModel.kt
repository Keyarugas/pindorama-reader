package eu.kanade.tachiyomi.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class PindoramaHomeViewModel(
    getHistory: GetHistory,
    getUpdates: GetUpdates,
    private val getChapter: GetChapter,
) : ViewModel() {
    companion object {
        internal fun recentUpdates(
            items: List<UpdatesWithRelations>,
            limit: Int = 5,
        ): List<UpdatesWithRelations> =
            items.distinctBy(UpdatesWithRelations::mangaId).take(limit)

        internal fun activity(history: List<HistoryWithRelations>, now: Long): ActivitySummary {
            val cutoff = now - 7.days.inWholeMilliseconds
            val recent = history.filter { (it.readAt?.time ?: 0L) >= cutoff }
            return ActivitySummary(recent.size, recent.distinctBy(HistoryWithRelations::mangaId).size)
        }
    }

    private val cutoff = Clock.System.now().minus(7.days)
    private val history = getHistory.subscribe("")
    private val updates = getUpdates.subscribe(
        instant = cutoff,
        unread = null,
        started = null,
        bookmarked = null,
        hideExcludedScanlators = false,
        includedCategories = emptyList(),
        excludedCategories = emptyList(),
    )

    val state: StateFlow<State> = combine(history, updates) { historyItems, updateItems ->
        historyItems to updateItems
    }.flatMapLatest { (historyItems, updateItems) ->
        flow {
            val latest = historyItems.firstOrNull()
            emit(
                State.Success(
                    continueReading = latest?.let { it to getChapter.await(it.chapterId) },
                    recentUpdates = recentUpdates(updateItems),
                    activity = activity(historyItems, Clock.System.now().toEpochMilliseconds()),
                ),
            )
        }
    }
        .catch { emit(State.Error) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State.Loading)

    data class ActivitySummary(val chaptersRead: Int, val worksRead: Int)
    data class Success(
        val continueReading: Pair<HistoryWithRelations, tachiyomi.domain.chapter.model.Chapter?>?,
        val recentUpdates: List<UpdatesWithRelations>,
        val activity: ActivitySummary,
    ) : State()
    sealed class State {
        data object Loading : State()
        data object Error : State()
    }
}
