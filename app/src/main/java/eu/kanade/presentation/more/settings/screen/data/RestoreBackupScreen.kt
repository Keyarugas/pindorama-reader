package eu.kanade.presentation.more.settings.screen.data

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.backup.BackupError
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.protectedBackupOperation
import eu.kanade.tachiyomi.data.backup.crypto.Argon2KeyDerivation
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.backup.restore.PreparedProtectedRestore
import eu.kanade.tachiyomi.data.backup.restore.ProtectedBackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.ProtectedRestoreLoader
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

class RestoreBackupScreen(
    private val uri: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel =
            assistedMetroViewModel<RestoreBackupViewModel, RestoreBackupViewModel.Factory> { create(uri = uri) }
        val state by viewModel.state.collectAsState()
        var password by remember { mutableStateOf("") }
        DisposableEffect(viewModel) { onDispose { viewModel.cancel() } }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_restore_backup),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumnWithAction(
                contentPadding = contentPadding,
                actionLabel = stringResource(MR.strings.action_restore),
                actionEnabled = state.canRestore && state.options.canRestore() && !state.working,
                onClickAction = {
                    if (viewModel.startRestore()) navigator.pop()
                },
            ) {
                if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                    item {
                        WarningBanner(MR.strings.restore_miui_warning)
                    }
                }

                if (state.protectedBackup) {
                    item { WarningBanner(MR.strings.pindorama_restore_policy) }
                }
                if (state.needsPassword && !state.working) {
                    item {
                        Text(stringResource(MR.strings.pindorama_restore_password), Modifier.padding(16.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { if (it.length <= Argon2KeyDerivation.MAX_PASSWORD_CHARS) password = it },
                            label = { Text(stringResource(MR.strings.password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                        TextButton(
                            enabled = password.isNotEmpty(),
                            onClick = {
                                viewModel.unlock(password.toCharArray())
                                password = ""
                            },
                        ) { Text(stringResource(MR.strings.action_restore)) }
                    }
                }
                if (state.working) {
                    item {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(MR.strings.pindorama_restore_working), Modifier.padding(16.dp))
                        TextButton(onClick = {
                            password = ""
                            viewModel.cancel()
                        }) { Text(stringResource(MR.strings.action_cancel)) }
                    }
                }
                if (state.complete) {
                    item { Text(stringResource(MR.strings.pindorama_restore_complete), Modifier.padding(16.dp)) }
                }
                if (state.libraryComplete) {
                    item {
                        Text(stringResource(MR.strings.pindorama_restore_library_complete), Modifier.padding(16.dp))
                    }
                    item {
                        Text(
                            stringResource(
                                when (state.visualPreferences) {
                                    VisualPreferenceImport.NOT_SELECTED ->
                                        MR.strings.pindorama_restore_visual_not_selected
                                    VisualPreferenceImport.PENDING -> MR.strings.pindorama_restore_visual_pending
                                    VisualPreferenceImport.COMPLETE -> MR.strings.pindorama_restore_visual_complete
                                    VisualPreferenceImport.FAILED -> MR.strings.pindorama_restore_visual_failed
                                },
                            ),
                            Modifier.padding(16.dp),
                        )
                    }
                }
                if (state.applicationStarted && !state.complete && !state.working) {
                    item { WarningBanner(MR.strings.pindorama_restore_partial) }
                }
                if (state.canRestore) {
                    item {
                        SectionCard {
                            RestoreOptions.options.filter {
                                !state.protectedBackup || (
                                    it.label != MR.strings.extensionStores &&
                                        it.label != MR.strings.source_settings
                                    )
                            }.forEach { option ->
                                LabeledCheckbox(
                                    label = stringResource(
                                        if (state.protectedBackup && option.label == MR.strings.app_settings) {
                                            MR.strings.pindorama_restore_visual_option
                                        } else {
                                            option.label
                                        },
                                    ),
                                    checked = option.getter(state.options),
                                    enabled = !state.working,
                                    onCheckedChange = {
                                        viewModel.toggle(option.setter, it)
                                    },
                                )
                            }
                        }
                    }
                }

                if (state.error != null) {
                    errorMessageItem(state.error)
                }
            }
        }
    }

    private fun LazyListScope.errorMessageItem(
        error: Any?,
    ) {
        item {
            SectionCard {
                Column(
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    val msg = buildAnnotatedString {
                        when (error) {
                            is MissingRestoreComponents -> {
                                appendLine(stringResource(MR.strings.backup_restore_content_full))
                                if (error.sources.isNotEmpty()) {
                                    appendLine()
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                        appendLine(stringResource(MR.strings.backup_restore_missing_sources))
                                    }
                                }
                                if (error.trackers.isNotEmpty()) {
                                    appendLine()
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                        appendLine(stringResource(MR.strings.backup_restore_missing_trackers))
                                    }
                                }
                            }

                            is InvalidRestore -> {
                                appendLine(
                                    stringResource(
                                        if (error.error == BackupError.BUSY) {
                                            MR.strings.pindorama_restore_busy
                                        } else {
                                            error.error.messageRes
                                        },
                                    ),
                                )
                            }

                            else -> {
                                appendLine(stringResource(MR.strings.pindorama_backup_operation_failed))
                            }
                        }
                    }

                    SelectionContainer {
                        Text(text = msg)
                    }
                }
            }
        }
    }
}

@AssistedInject
class RestoreBackupViewModel(
    @Assisted private val uri: String,
    private val backupFileValidator: BackupFileValidator,
    private val context: Context,
    private val protectedLoader: ProtectedRestoreLoader,
    private val protectedRestorer: ProtectedBackupRestorer,
) : ViewModel() {

    val state: StateFlow<RestoreBackupViewModel.State>
        field = MutableStateFlow<RestoreBackupViewModel.State>(State())

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(uri: String): RestoreBackupViewModel
    }

    private var prepared: PreparedProtectedRestore? = null
    private var operation: Job? = null

    init {
        operation = viewModelScope.launchIO {
            try {
                if (protectedLoader.isProtected(uri.toUri())) {
                    state.update { it.copy(protectedBackup = true, needsPassword = true) }
                } else {
                    validate(uri.toUri())
                }
            } catch (_: CancellationException) {
                setError(InvalidRestore(BackupError.CANCELLED), false)
            } catch (e: Exception) {
                setError(InvalidRestore(BackupError.from(e)), false)
            }
        }
    }

    fun unlock(password: CharArray) {
        if (state.value.working || !state.value.needsPassword) {
            password.fill('\u0000')
            return
        }
        state.update { it.copy(working = true, error = null, canRestore = false) }
        operation = viewModelScope.launchIO {
            try {
                val value = protectedBackupOperation { protectedLoader.prepare(uri.toUri(), password) }
                currentCoroutineContext().ensureActive()
                prepared = value
                state.update {
                    it.copy(
                        canRestore = true,
                        needsPassword = false,
                        options = it.options.copy(appSettings = false, sourceSettings = false, extensionStores = false),
                    )
                }
            } catch (_: CancellationException) {
                prepared = null
                setError(InvalidRestore(BackupError.CANCELLED), false)
            } catch (e: Exception) {
                prepared = null
                setError(InvalidRestore(BackupError.from(e)), false)
            } finally {
                password.fill('\u0000')
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                password.fill('\u0000')
                if (cause is CancellationException) {
                    prepared = null
                    state.update {
                        it.copy(
                            error = InvalidRestore(BackupError.CANCELLED),
                            canRestore = false,
                            needsPassword = true,
                        )
                    }
                }
                state.update { it.copy(working = false) }
            }
        }
    }

    fun cancel() {
        operation?.cancel()
        prepared = null
        state.update {
            it.copy(
                error = if (it.working) InvalidRestore(BackupError.CANCELLED) else it.error,
                canRestore = false,
                needsPassword = it.protectedBackup && !it.applicationStarted,
            )
        }
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }

    fun toggle(setter: (RestoreOptions, Boolean) -> RestoreOptions, enabled: Boolean) {
        if (state.value.working) return
        state.update {
            it.copy(
                options = setter(it.options, enabled),
            )
        }
    }

    fun startRestore(): Boolean {
        if (!state.value.canRestore || state.value.working || !state.value.options.canRestore()) return false
        if (state.value.protectedBackup) {
            val value = prepared ?: return false
            prepared = null
            val options = state.value.options
            state.update { it.copy(working = true, canRestore = false, error = null) }
            operation = viewModelScope.launchIO {
                try {
                    currentCoroutineContext().ensureActive()
                    state.update { it.copy(applicationStarted = true) }
                    protectedRestorer.restore(value, options.copy(appSettings = false))
                    state.update {
                        it.copy(
                            libraryComplete = true,
                            visualPreferences = if (options.appSettings) {
                                VisualPreferenceImport.PENDING
                            } else {
                                VisualPreferenceImport.NOT_SELECTED
                            },
                        )
                    }
                    if (options.appSettings) {
                        protectedRestorer.importVisualPreferences(value)
                        state.update { it.copy(visualPreferences = VisualPreferenceImport.COMPLETE) }
                    }
                    currentCoroutineContext().ensureActive()
                    state.update { it.copy(complete = true) }
                } catch (_: CancellationException) {
                    setError(InvalidRestore(BackupError.CANCELLED), false)
                } catch (e: Exception) {
                    val error = BackupError.from(e)
                    if (!state.value.libraryComplete &&
                        (error == BackupError.BUSY || error == BackupError.PENDING_TRACKING)
                    ) {
                        // These refusals happen before library mutation; retain authenticated data for retry.
                        prepared = value
                        state.update {
                            it.copy(error = InvalidRestore(error), canRestore = true, applicationStarted = false)
                        }
                    } else {
                        setError(InvalidRestore(error), false)
                    }
                }
            }.also { job ->
                job.invokeOnCompletion { cause ->
                    state.update {
                        it.copy(
                            working = false,
                            complete = it.complete && cause == null,
                            error = if (cause is CancellationException) {
                                InvalidRestore(BackupError.CANCELLED)
                            } else {
                                it.error
                            },
                            visualPreferences = if (it.visualPreferences == VisualPreferenceImport.PENDING) {
                                VisualPreferenceImport.FAILED
                            } else {
                                it.visualPreferences
                            },
                        )
                    }
                }
            }
            return false
        }
        BackupRestoreJob.start(
            workManager = context.workManager,
            uri = uri.toUri(),
            options = state.value.options,
        )
        return true
    }

    private suspend fun validate(uri: Uri) {
        val results = try {
            backupFileValidator.validate(uri)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            setError(
                error = InvalidRestore(BackupError.from(e)),
                canRestore = false,
            )
            return
        }

        if (results.missingSources.isNotEmpty() || results.missingTrackers.isNotEmpty()) {
            setError(
                error = MissingRestoreComponents(results.missingSources, results.missingTrackers),
                canRestore = true,
            )
            return
        }

        setError(error = null, canRestore = true)
    }

    private fun setError(error: Any?, canRestore: Boolean) {
        state.update {
            it.copy(
                error = error,
                canRestore = canRestore,
            )
        }
    }

    @Immutable
    data class State(
        val protectedBackup: Boolean = false,
        val needsPassword: Boolean = false,
        val working: Boolean = false,
        val applicationStarted: Boolean = false,
        val complete: Boolean = false,
        val libraryComplete: Boolean = false,
        val visualPreferences: VisualPreferenceImport = VisualPreferenceImport.NOT_SELECTED,
        val error: Any? = null,
        val canRestore: Boolean = false,
        val options: RestoreOptions = RestoreOptions(),
    ) {
        val failure: BackupError? get() = (error as? InvalidRestore)?.error
    }
}

enum class VisualPreferenceImport {
    NOT_SELECTED,
    PENDING,
    COMPLETE,
    FAILED,
}

private data class MissingRestoreComponents(
    val sources: List<String>,
    val trackers: List<String>,
)

private data class InvalidRestore(val error: BackupError)
