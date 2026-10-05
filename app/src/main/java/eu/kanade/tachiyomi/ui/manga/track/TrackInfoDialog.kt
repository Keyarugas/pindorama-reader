package eu.kanade.tachiyomi.ui.manga.track

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.domain.track.interactor.ManualTracking
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.RegisterTracking
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.track.TrackChapterSelector
import eu.kanade.presentation.track.TrackDateSelector
import eu.kanade.presentation.track.TrackInfoDialogHome
import eu.kanade.presentation.track.TrackScoreSelector
import eu.kanade.presentation.track.TrackStatusSelector
import eu.kanade.presentation.track.TrackerSearch
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.util.lang.convertEpochMillisZone
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.AlertDialogContent
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

data class TrackInfoDialogHomeScreen(
    private val mangaId: Long,
    private val mangaTitle: String,
    private val sourceId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = assistedMetroViewModel<Model, Model.Factory> { create(mangaId = mangaId, sourceId = sourceId) }

        val dateFormat = remember { UiPreferences.dateFormat(context.appGraph.uiPreferences.dateFormat.get()) }
        val state by viewModel.state.collectAsState()

        TrackInfoDialogHome(
            trackItems = state.trackItems,
            dateFormat = dateFormat,
            onStatusClick = {
                viewModel.openEditor(it, navigator) { target ->
                    TrackStatusSelectorScreen(
                        target = target,
                        serviceId = it.tracker.id,
                    )
                }
            },
            onChapterClick = {
                viewModel.openEditor(it, navigator) { target ->
                    TrackChapterSelectorScreen(
                        target = target,
                        serviceId = it.tracker.id,
                    )
                }
            },
            onScoreClick = {
                viewModel.openEditor(it, navigator) { target ->
                    TrackScoreSelectorScreen(
                        target = target,
                        serviceId = it.tracker.id,
                    )
                }
            },
            onStartDateEdit = {
                viewModel.openEditor(it, navigator) { target ->
                    TrackDateSelectorScreen(
                        target = target,
                        serviceId = it.tracker.id,
                        start = true,
                    )
                }
            },
            onEndDateEdit = {
                viewModel.openEditor(it, navigator) { target ->
                    TrackDateSelectorScreen(
                        target = target,
                        serviceId = it.tracker.id,
                        start = false,
                    )
                }
            },
            onNewSearch = {
                if (it.tracker is EnhancedTracker) {
                    viewModel.registerEnhancedTracking(it)
                } else {
                    viewModel.openSearch(it, mangaTitle, navigator)
                }
            },
            onOpenInBrowser = { openTrackerInBrowser(context, it) },
            onRemoved = {
                viewModel.openEditor(it, navigator) { target ->
                    TrackerRemoveScreen(
                        target = target,
                        serviceId = it.tracker.id,
                    )
                }
            },
            onCopyLink = { context.copyTrackerLink(it) },
            onTogglePrivate = viewModel::togglePrivate,
        )
    }

    /**
     * Opens registered tracker url in browser
     */
    private fun openTrackerInBrowser(context: Context, trackItem: TrackItem) {
        val url = trackItem.track?.remoteUrl ?: return
        if (url.isNotBlank()) {
            context.openInBrowser(url)
        }
    }

    private fun Context.copyTrackerLink(trackItem: TrackItem) {
        val url = trackItem.track?.remoteUrl ?: return
        if (url.isNotBlank()) {
            copyToClipboard(url, url)
        }
    }

    @AssistedInject
    class Model(
        @Assisted private val mangaId: Long,
        @Assisted private val sourceId: Long,
        private val context: Context,
        private val getTracks: GetTracks,
        private val manualTracking: ManualTracking,
        private val registerTracking: RegisterTracking,
        private val trackerManager: TrackerManager,
        private val sourceManager: SourceManager,
        private val refreshTracks: RefreshTracks,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State())

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(mangaId: Long, sourceId: Long): Model
        }

        init {
            val generation = LibraryOperationCoordinator.shared.generation
            viewModelScope.launch {
                refreshTrackers(generation)
            }

            viewModelScope.launch {
                getTracks.subscribe(mangaId)
                    .catch { logcat(LogPriority.ERROR, it) }
                    .distinctUntilChanged()
                    .map { it.mapToTrackItem() }
                    .collectLatest { trackItems -> state.update { it.copy(trackItems = trackItems) } }
            }
        }

        fun openEditor(item: TrackItem, navigator: Navigator, screen: (ManualTracking.Target) -> Screen) {
            val generation = LibraryOperationCoordinator.shared.generation
            val expected = item.track ?: return
            viewModelScope.launch {
                val target = manualTracking.loadTarget(expected, generation) ?: return@launch
                navigator.push(screen(target))
            }
        }

        fun openSearch(item: TrackItem, mangaTitle: String, navigator: Navigator) {
            val generation = LibraryOperationCoordinator.shared.generation
            viewModelScope.launch {
                val session =
                    registerTracking.loadSession(mangaId, sourceId, item.tracker.id, generation) ?: return@launch
                navigator.push(TrackerSearchScreen(session, item.track?.title ?: mangaTitle, item.track?.remoteUrl))
            }
        }

        fun registerEnhancedTracking(item: TrackItem) {
            val generation = LibraryOperationCoordinator.shared.generation
            viewModelScope.launchNonCancellable {
                val session = registerTracking.loadSession(mangaId, sourceId, item.tracker.id, generation)
                    ?: return@launchNonCancellable
                try {
                    if (registerTracking.matchAndBind(session) == false) {
                        withUIContext { context.toast(MR.strings.error_no_match) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    withUIContext { context.toast(MR.strings.error_no_match) }
                }
            }
        }

        private suspend fun refreshTrackers(generation: Long) {
            refreshTracks.await(mangaId, generation)
                .filter { it.first != null }
                .forEach { (track, e) ->
                    logcat(LogPriority.ERROR, e) {
                        "Failed to refresh track data mangaId=$mangaId for service ${track!!.id}"
                    }
                    withUIContext {
                        context.toast(
                            context.stringResource(
                                MR.strings.track_error,
                                track!!.name,
                                e.message ?: "",
                            ),
                        )
                    }
                }
        }

        fun togglePrivate(item: TrackItem) {
            val generation = LibraryOperationCoordinator.shared.generation
            val expected = item.track ?: return
            viewModelScope.launchNonCancellable {
                val target = manualTracking.loadTarget(expected, generation) ?: return@launchNonCancellable
                manualTracking.update(target) { service, current ->
                    service.setRemotePrivate(current, !current.private)
                }
            }
        }

        private suspend fun List<Track>.mapToTrackItem(): List<TrackItem> {
            val loggedInTrackers = trackerManager.loggedInTrackers()
            val source = sourceManager.getOrStub(sourceId)
            return loggedInTrackers
                // Map to TrackItem
                .map { service -> TrackItem(find { it.trackerId == service.id }, service) }
                // Show only if the service supports this manga's source
                .filter { (it.tracker as? EnhancedTracker)?.accept(source) ?: true }
        }

        @Immutable
        data class State(
            val trackItems: List<TrackItem> = emptyList(),
        )
    }
}

data class TrackStatusSelectorScreen(
    private val target: ManualTracking.Target,
    private val serviceId: Long,
) : Screen() {

    private val track: Track get() = target.track

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> { create(track = track, trackerId = serviceId) }
        val state by viewModel.state.collectAsState()
        TrackStatusSelector(
            selection = state.selection,
            onSelectionChange = viewModel::setSelection,
            selections = remember { viewModel.getSelections() },
            onConfirm = {
                viewModel.setStatus(target)
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
        )
    }

    @AssistedInject
    class Model(
        @Assisted private val track: Track,
        @Assisted private val trackerId: Long,
        trackerManager: TrackerManager,
        private val manualTracking: ManualTracking,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State(track.status))

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long): Model
        }

        val tracker = trackerManager.get(trackerId)!!

        fun getSelections(): Map<Long, StringResource?> {
            return tracker.getStatusList().associateWith { tracker.getStatus(it) }
        }

        fun setSelection(selection: Long) {
            state.update { it.copy(selection = selection) }
        }

        fun setStatus(target: ManualTracking.Target) {
            val selection = state.value.selection
            viewModelScope.launchNonCancellable {
                manualTracking.update(target) { service, current -> service.setRemoteStatus(current, selection) }
            }
        }

        @Immutable
        data class State(
            val selection: Long,
        )
    }
}

data class TrackChapterSelectorScreen(
    private val target: ManualTracking.Target,
    private val serviceId: Long,
) : Screen() {

    private val track: Track get() = target.track

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> { create(track = track, trackerId = serviceId) }
        val state by viewModel.state.collectAsState()

        TrackChapterSelector(
            selection = state.selection,
            onSelectionChange = viewModel::setSelection,
            range = remember { viewModel.getRange() },
            onConfirm = {
                viewModel.setChapter(target)
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
        )
    }

    @AssistedInject
    class Model(
        @Assisted private val track: Track,
        @Assisted private val trackerId: Long,
        trackerManager: TrackerManager,
        private val manualTracking: ManualTracking,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State(track.lastChapterRead.toInt()))

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long): Model
        }

        val tracker = trackerManager.get(trackerId)!!

        fun getRange(): Iterable<Int> {
            val endRange = if (track.totalChapters > 0) {
                track.totalChapters
            } else {
                10000
            }
            return 0..endRange.toInt()
        }

        fun setSelection(selection: Int) {
            state.update { it.copy(selection = selection) }
        }

        fun setChapter(target: ManualTracking.Target) {
            val selection = state.value.selection
            viewModelScope.launchNonCancellable {
                manualTracking.update(target) { service, current ->
                    service.setRemoteLastChapterRead(current, selection)
                }
            }
        }

        @Immutable
        data class State(
            val selection: Int,
        )
    }
}

data class TrackScoreSelectorScreen(
    private val target: ManualTracking.Target,
    private val serviceId: Long,
) : Screen() {

    private val track: Track get() = target.track

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> { create(track = track, trackerId = serviceId) }
        val state by viewModel.state.collectAsState()

        TrackScoreSelector(
            selection = state.selection,
            onSelectionChange = viewModel::setSelection,
            selections = remember { viewModel.getSelections() },
            onConfirm = {
                viewModel.setScore(target)
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
        )
    }

    @AssistedInject
    class Model(
        @Assisted private val track: Track,
        @Assisted private val trackerId: Long,
        trackerManager: TrackerManager,
        private val manualTracking: ManualTracking,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State(""))

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long): Model
        }

        val tracker = trackerManager.get(trackerId)!!

        init {
            tracker.displayScore(track).let(::setSelection)
        }

        fun getSelections(): List<String> {
            return tracker.getScoreList()
        }

        fun setSelection(selection: String) {
            state.update { it.copy(selection = selection) }
        }

        fun setScore(target: ManualTracking.Target) {
            val selection = state.value.selection
            viewModelScope.launchNonCancellable {
                manualTracking.update(target) { service, current -> service.setRemoteScore(current, selection) }
            }
        }

        @Immutable
        data class State(
            val selection: String,
        )
    }
}

data class TrackDateSelectorScreen(
    private val target: ManualTracking.Target,
    private val serviceId: Long,
    private val start: Boolean,
) : Screen() {

    private val track: Track get() = target.track

    @Transient
    private val selectableDates = object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
            val targetDate = Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC)

            // Disallow future dates
            if (targetDate > Clock.System.now().toLocalDateTime(TimeZone.UTC)) return false

            return when {
                // Disallow setting start date after finish date
                start && track.finishDate > 0 -> {
                    val finishDate = Instant.fromEpochMilliseconds(track.finishDate).toLocalDateTime(TimeZone.UTC)
                    targetDate <= finishDate
                }
                // Disallow setting finish date before start date
                !start && track.startDate > 0 -> {
                    val startDate = Instant.fromEpochMilliseconds(track.startDate).toLocalDateTime(TimeZone.UTC)
                    startDate <= targetDate
                }
                else -> {
                    true
                }
            }
        }

        override fun isSelectableYear(year: Int): Boolean {
            // Disallow future years
            if (year > Clock.System.now().toLocalDateTime(TimeZone.UTC).year) return false

            return when {
                // Disallow setting start year after finish year
                start && track.finishDate > 0 -> {
                    val finishDate = Instant.fromEpochMilliseconds(track.finishDate).toLocalDateTime(TimeZone.UTC)
                    year <= finishDate.year
                }
                // Disallow setting finish year before start year
                !start && track.startDate > 0 -> {
                    val startDate = Instant.fromEpochMilliseconds(track.startDate).toLocalDateTime(TimeZone.UTC)
                    startDate.year <= year
                }
                else -> {
                    true
                }
            }
        }
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(track = track, trackerId = serviceId, start = start)
        }

        val canRemove = if (start) {
            track.startDate > 0
        } else {
            track.finishDate > 0
        }
        TrackDateSelector(
            title = if (start) {
                stringResource(MR.strings.track_started_reading_date)
            } else {
                stringResource(MR.strings.track_finished_reading_date)
            },
            initialSelectedDateMillis = viewModel.initialSelection,
            selectableDates = selectableDates,
            onConfirm = {
                viewModel.setDate(target, it)
                navigator.pop()
            },
            onRemove = { viewModel.confirmRemoveDate(navigator, target) }.takeIf { canRemove },
            onDismissRequest = navigator::pop,
        )
    }

    @AssistedInject
    class Model(
        @Assisted private val track: Track,
        @Assisted private val trackerId: Long,
        @Assisted private val start: Boolean,
        trackerManager: TrackerManager,
        private val manualTracking: ManualTracking,
    ) : ViewModel() {

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long, start: Boolean): Model
        }

        private val tracker = trackerManager.get(trackerId)!!

        // In UTC
        val initialSelection: Long
            get() {
                val millis = (if (start) track.startDate else track.finishDate)
                    .takeIf { it != 0L }
                    ?: Clock.System.now().toEpochMilliseconds()
                return millis.convertEpochMillisZone(TimeZone.currentSystemDefault(), TimeZone.UTC)
            }

        // In UTC
        fun setDate(target: ManualTracking.Target, millis: Long) {
            // Convert to local time
            val localMillis = millis.convertEpochMillisZone(TimeZone.UTC, TimeZone.currentSystemDefault())
            viewModelScope.launchNonCancellable {
                manualTracking.update(target) { service, current ->
                    if (start) {
                        service.setRemoteStartDate(current, localMillis)
                    } else {
                        service.setRemoteFinishDate(current, localMillis)
                    }
                }
            }
        }

        fun confirmRemoveDate(navigator: Navigator, target: ManualTracking.Target) {
            navigator.push(TrackDateRemoverScreen(target, tracker.id, start))
        }
    }
}

data class TrackDateRemoverScreen(
    private val target: ManualTracking.Target,
    private val serviceId: Long,
    private val start: Boolean,
) : Screen() {

    private val track: Track get() = target.track

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(track = track, trackerId = serviceId, start = start)
        }
        AlertDialogContent(
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
            icon = {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Delete,
                    contentDescription = null,
                )
            },
            title = {
                Text(
                    text = stringResource(MR.strings.track_remove_date_conf_title),
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                val serviceName = viewModel.getServiceName()
                Text(
                    text = if (start) {
                        stringResource(MR.strings.track_remove_start_date_conf_text, serviceName)
                    } else {
                        stringResource(MR.strings.track_remove_finish_date_conf_text, serviceName)
                    },
                )
            },
            buttons = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small, Alignment.End),
                ) {
                    TextButton(onClick = navigator::pop) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                    FilledTonalButton(
                        onClick = {
                            viewModel.removeDate(target)
                            navigator.popUntil { it is TrackInfoDialogHomeScreen }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text(text = stringResource(MR.strings.action_remove))
                    }
                }
            },
        )
    }

    @AssistedInject
    class Model(
        @Assisted private val track: Track,
        @Assisted private val trackerId: Long,
        @Assisted private val start: Boolean,
        trackerManager: TrackerManager,
        private val manualTracking: ManualTracking,
    ) : ViewModel() {

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long, start: Boolean): Model
        }

        private val tracker = trackerManager.get(trackerId)!!

        fun getServiceName() = tracker.name

        fun removeDate(target: ManualTracking.Target) {
            viewModelScope.launchNonCancellable {
                manualTracking.update(target) { service, current ->
                    if (start) {
                        service.setRemoteStartDate(current, 0)
                    } else {
                        service.setRemoteFinishDate(current, 0)
                    }
                }
            }
        }
    }
}

data class TrackerSearchScreen(
    private val session: RegisterTracking.Session,
    private val initialQuery: String,
    private val currentUrl: String?,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(
                session = session,
                currentUrl = currentUrl,
                initialQuery = initialQuery,
            )
        }

        val state by viewModel.state.collectAsState()

        val textFieldState = rememberTextFieldState(initialQuery)
        TrackerSearch(
            state = textFieldState,
            onDispatchQuery = { viewModel.trackingSearch(textFieldState.text.toString()) },
            queryResult = state.queryResult,
            selected = state.selected,
            onSelectedChange = viewModel::updateSelection,
            onConfirmSelection = f@{ private: Boolean ->
                val selected = state.selected ?: return@f
                selected.private = private
                viewModel.registerTracking(selected)
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
            supportsPrivateTracking = viewModel.supportsPrivateTracking,
        )
    }

    @AssistedInject
    class Model(
        @Assisted private val session: RegisterTracking.Session,
        @Assisted private val currentUrl: String?,
        @Assisted initialQuery: String,
        trackerManager: TrackerManager,
        private val registerTracking: RegisterTracking,
        private val context: Context,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State())

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(
                session: RegisterTracking.Session,
                currentUrl: String?,
                initialQuery: String,
            ): Model
        }

        private val tracker = trackerManager.get(session.trackerId)!!

        val supportsPrivateTracking = tracker.supportsPrivateTracking

        init {
            // Run search on first launch
            if (initialQuery.isNotBlank()) {
                trackingSearch(initialQuery)
            }
        }

        fun trackingSearch(query: String) {
            val generation = LibraryOperationCoordinator.shared.generation
            if (generation != session.generation) return
            viewModelScope.launch {
                // To show loading state
                state.update { it.copy(queryResult = null, selected = null) }

                val result = withIOContext {
                    try {
                        val results = registerTracking.search(session, query) ?: return@withIOContext null
                        Result.success(results)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Result.failure(IllegalStateException("TRACKING_SEARCH_FAILED"))
                    }
                }
                if (result == null || generation != LibraryOperationCoordinator.shared.generation) return@launch
                state.update { oldState ->
                    oldState.copy(
                        queryResult = result,
                        selected = result.getOrNull()?.find { it.tracking_url == currentUrl },
                    )
                }
            }
        }

        fun registerTracking(item: TrackSearch) {
            viewModelScope.launchNonCancellable {
                try {
                    registerTracking.bind(session, item)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    withUIContext {
                        if (session.generation == LibraryOperationCoordinator.shared.generation) {
                            context.toast(MR.strings.unknown_error)
                        }
                    }
                }
            }
        }

        fun updateSelection(selected: TrackSearch) {
            state.update { it.copy(selected = selected) }
        }

        @Immutable
        data class State(
            val queryResult: Result<List<TrackSearch>>? = null,
            val selected: TrackSearch? = null,
        )
    }
}

data class TrackerRemoveScreen(
    private val target: ManualTracking.Target,
    private val serviceId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(trackerId = serviceId)
        }
        val serviceName = viewModel.getName()
        var removeRemoteTrack by remember { mutableStateOf(false) }
        AlertDialogContent(
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
            icon = {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Delete,
                    contentDescription = null,
                )
            },
            title = {
                Text(
                    text = stringResource(MR.strings.track_delete_title, serviceName),
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    Text(
                        text = stringResource(MR.strings.track_delete_text, serviceName),
                    )

                    if (viewModel.isDeletable()) {
                        LabeledCheckbox(
                            label = stringResource(MR.strings.track_delete_remote_text, serviceName),
                            checked = removeRemoteTrack,
                            onCheckedChange = { removeRemoteTrack = it },
                        )
                    }
                }
            },
            buttons = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        MaterialTheme.padding.small,
                        Alignment.End,
                    ),
                ) {
                    TextButton(onClick = navigator::pop) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                    FilledTonalButton(
                        onClick = {
                            viewModel.remove(target, removeRemoteTrack)
                            navigator.pop()
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                }
            },
        )
    }

    @AssistedInject
    class Model(
        @Assisted private val trackerId: Long,
        private val manualTracking: ManualTracking,
        trackerManager: TrackerManager,
    ) : ViewModel() {

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(trackerId: Long): Model
        }

        private val tracker = trackerManager.get(trackerId)!!

        fun getName() = tracker.name

        fun isDeletable() = tracker is DeletableTracker

        fun remove(target: ManualTracking.Target, removeRemote: Boolean) {
            viewModelScope.launchNonCancellable { manualTracking.unlink(target, removeRemote) }
        }
    }
}
