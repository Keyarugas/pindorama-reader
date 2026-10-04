package eu.kanade.tachiyomi.ui.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.manga.interactor.AddMangaToLibrary
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.tachiyomi.core.security.PrivateContentSessionState
import eu.kanade.tachiyomi.ui.security.PrivateContentSessionManager
import eu.kanade.tachiyomi.ui.security.PrivateContentVisibility
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaWithChapterCount

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class HistoryViewModel(
    private val privateVisibility: PrivateContentVisibility,
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga,
    private val getHistory: GetHistory,
    private val getManga: GetManga,
    private val getNextChapters: GetNextChapters,
    private val libraryPreferences: LibraryPreferences,
    private val removeHistory: RemoveHistory,
    private val setMangaCategories: SetMangaCategories,
    private val addMangaToLibrary: AddMangaToLibrary,
) : ViewModel() {

    val snackbarHostState: SnackbarHostState = SnackbarHostState()

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private val searchQuery = MutableStateFlow<String?>(null)

    private val dialog = MutableStateFlow<Dialog?>(null)

    init {
        PrivateContentSessionManager.session.state.onEach {
            if (it != PrivateContentSessionState.UNLOCKED) {
                dialog.value = null
                searchQuery.value = null
            }
        }.launchIn(viewModelScope)
    }

    private val history = searchQuery
        .flatMapLatest { query ->
            privateVisibility.filter(getHistory.subscribe(query ?: "")) { it.mangaId }
                .distinctUntilChanged()
                .catch { error ->
                    logcat(LogPriority.ERROR, error)
                    _events.send(Event.InternalError)
                }
                .map { it.toHistoryUiModels() }
                .flowOn(Dispatchers.IO)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<State> = combine(
        searchQuery,
        history,
        dialog,
    ) { searchQuery, history, dialog ->
        State(searchQuery = searchQuery, list = history, dialog = dialog)
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, State())

    private fun List<HistoryWithRelations>.toHistoryUiModels(): List<HistoryUiModel> {
        return map { HistoryUiModel.Item(it) }
            .insertSeparators { before, after ->
                val beforeDate = before?.item?.readAt?.time?.toLocalDate()
                val afterDate = after?.item?.readAt?.time?.toLocalDate()
                when {
                    beforeDate != afterDate && afterDate != null -> HistoryUiModel.Header(afterDate)
                    // Return null to avoid adding a separator between two items.
                    else -> null
                }
            }
    }

    suspend fun getNextChapter(): Chapter? {
        return withIOContext { getNextChapters.await(onlyUnread = false).firstOrNull() }
    }

    fun getNextChapterForManga(mangaId: Long, chapterId: Long) {
        viewModelScope.launchIO {
            sendNextChapterEvent(getNextChapters.await(mangaId, chapterId, onlyUnread = false))
        }
    }

    private suspend fun sendNextChapterEvent(chapters: List<Chapter>) {
        val chapter = chapters.firstOrNull()
        _events.send(Event.OpenChapter(chapter))
    }

    fun removeFromHistory(target: RemoveHistory.Target) {
        viewModelScope.launchIO {
            removeHistory.await(target)
        }
    }

    fun removeAllFromHistory(target: RemoveHistory.Target) {
        viewModelScope.launchIO {
            removeHistory.await(target.manga, target.generation)
        }
    }

    fun removeAllHistory(generation: Long) {
        viewModelScope.launchIO {
            val result = removeHistory.awaitAll(generation)
            if (!result) return@launchIO
            _events.send(Event.HistoryCleared)
        }
    }

    fun updateSearchQuery(query: String?) {
        searchQuery.update { query }
    }

    fun setDialog(dialog: Dialog?) {
        this.dialog.value = dialog
        if (dialog is Dialog.Delete) {
            viewModelScope.launchIO {
                val target = removeHistory.loadTarget(dialog.history, dialog.generation)
                this@HistoryViewModel.dialog.update { current ->
                    if (current !== dialog) return@update current
                    target?.let { dialog.copy(target = it) }
                }
            }
        }
    }

    fun moveMangaToCategoriesAndAddToLibrary(manga: Manga, categories: List<Long>, generation: Long) {
        viewModelScope.launchIO { addMangaToLibrary.await(manga, categories, generation) }
    }

    fun addFavorite(mangaId: Long) {
        val coordinator = LibraryOperationCoordinator.shared
        val generation = coordinator.generation
        viewModelScope.launchIO {
            coordinator.operation {
                if (generation != coordinator.generation) return@operation
                val manga = getManga.await(mangaId) ?: return@operation
                val duplicates = getDuplicateLibraryManga(manga)
                if (!manga.favorite && duplicates.isNotEmpty()) {
                    dialog.update { Dialog.DuplicateManga(manga, duplicates, generation) }
                    return@operation
                }
                prepareAdd(manga, generation)
            }
        }
    }

    fun addFavorite(manga: Manga, generation: Long) {
        viewModelScope.launchIO { prepareAdd(manga, generation) }
    }

    private suspend fun prepareAdd(manga: Manga, generation: Long) {
        val coordinator = LibraryOperationCoordinator.shared
        coordinator.operation {
            val selection = setMangaCategories.loadSelection(listOf(manga), generation) ?: return@operation
            val current = selection.mangas.single()
            val defaultId = libraryPreferences.defaultCategory.get().toLong()
            val default = selection.categories.find { it.id == defaultId }
            when {
                default != null -> addMangaToLibrary.await(current, listOf(default.id), generation)
                defaultId == 0L || selection.categories.isEmpty() ->
                    addMangaToLibrary.await(current, emptyList(), generation)
                else -> dialog.update {
                    Dialog.ChangeCategory(
                        current,
                        selection.categories.mapAsCheckboxState {
                            it.id in selection.categoryIds[current.id].orEmpty()
                        },
                        generation,
                    )
                }
            }
        }
    }

    fun showMigrateDialog(target: Manga, current: Manga) {
        dialog.update { Dialog.Migrate(target = target, current = current) }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val list: List<HistoryUiModel>? = null,
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data class DeleteAll(val generation: Long = LibraryOperationCoordinator.shared.generation) : Dialog
        data class Delete(
            val history: HistoryWithRelations,
            val generation: Long = LibraryOperationCoordinator.shared.generation,
            val target: RemoveHistory.Target? = null,
        ) : Dialog
        data class DuplicateManga(
            val manga: Manga,
            val duplicates: List<MangaWithChapterCount>,
            val generation: Long,
        ) : Dialog
        data class ChangeCategory(
            val manga: Manga,
            val initialSelection: List<CheckboxState<Category>>,
            val generation: Long,
        ) : Dialog
        data class Migrate(val target: Manga, val current: Manga) : Dialog
    }

    sealed interface Event {
        data class OpenChapter(val chapter: Chapter?) : Event
        data object InternalError : Event
        data object HistoryCleared : Event
    }
}
