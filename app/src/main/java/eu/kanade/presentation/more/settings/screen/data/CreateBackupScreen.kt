package eu.kanade.presentation.more.settings.screen.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.backup.BackupError
import eu.kanade.tachiyomi.data.backup.BackupException
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.ProtectedBackupPayload
import eu.kanade.tachiyomi.data.backup.create.confirmedBackupPassword
import eu.kanade.tachiyomi.data.backup.create.protectedBackupOperation
import eu.kanade.tachiyomi.data.backup.crypto.Argon2KeyDerivation
import eu.kanade.tachiyomi.data.backup.crypto.BackupCryptoException
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class CreateBackupScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<CreateBackupViewModel>()
        val state by viewModel.state.collectAsState()
        // Deliberately not rememberSaveable/SavedStateHandle: passwords must not reach saved state.
        var password by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        DisposableEffect(viewModel) {
            onDispose { viewModel.cancel() }
        }

        val chooseBackupDir = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/*"),
        ) {
            if (it == null) {
                viewModel.discardPrepared()
            } else if (viewModel.createBackup(context, it)) {
                navigator.pop()
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_create_backup),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            LazyColumnWithAction(
                contentPadding = contentPadding,
                actionLabel = stringResource(MR.strings.action_create),
                actionEnabled = state.options.canCreate() && !state.busy &&
                    (!state.protectedBackup || (password.isNotEmpty() && confirmation.isNotEmpty())),
                onClickAction = {
                    if (!BackupCreateJob.isManualJobRunning(context)) {
                        if (viewModel.prepare(password.toCharArray(), confirmation.toCharArray())) {
                            password = ""
                            confirmation = ""
                            try {
                                chooseBackupDir.launch(
                                    if (state.protectedBackup) {
                                        BackupCreator.getProtectedFilename()
                                    } else {
                                        BackupCreator.getFilename()
                                    },
                                )
                            } catch (_: ActivityNotFoundException) {
                                viewModel.discardPrepared()
                                context.toast(MR.strings.file_picker_error)
                            } catch (_: Exception) {
                                viewModel.destinationFailed()
                            }
                        }
                    } else {
                        context.toast(MR.strings.backup_in_progress)
                    }
                },
            ) {
                item {
                    LabeledCheckbox(
                        label = stringResource(MR.strings.pindorama_backup_protected_option),
                        checked = state.protectedBackup,
                        enabled = !state.busy,
                        onCheckedChange = {
                            password = ""
                            confirmation = ""
                            viewModel.setProtected(it)
                        },
                    )
                    WarningBanner(
                        if (state.protectedBackup) {
                            MR.strings.pindorama_backup_protected_experimental
                        } else {
                            MR.strings.pindorama_backup_conventional_warning
                        },
                    )
                }

                if (state.protectedBackup) {
                    item {
                        Text(
                            stringResource(MR.strings.pindorama_backup_password_warning),
                            modifier = Modifier.padding(16.dp),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                if (it.length <= Argon2KeyDerivation.MAX_PASSWORD_CHARS) {
                                    password = it
                                } else {
                                    viewModel.invalidPassword()
                                }
                            },
                            label = { Text(stringResource(MR.strings.password)) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            enabled = !state.busy,
                        )
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = {
                                if (it.length <= Argon2KeyDerivation.MAX_PASSWORD_CHARS) {
                                    confirmation = it
                                } else {
                                    viewModel.invalidPassword()
                                }
                            },
                            label = { Text(stringResource(MR.strings.pindorama_backup_password_confirm)) },
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            enabled = !state.busy,
                        )
                    }
                }
                item {
                    if (state.working) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(16.dp))
                        Text(stringResource(MR.strings.creating_backup), Modifier.padding(horizontal = 16.dp))
                        TextButton(onClick = viewModel::cancel) { Text(stringResource(MR.strings.action_cancel)) }
                    }
                    state.error?.let { Text(stringResource(it.messageRes), Modifier.padding(16.dp)) }
                    if (state.complete) {
                        Text(stringResource(MR.strings.pindorama_backup_protected_complete), Modifier.padding(16.dp))
                    }
                    if (state.cancelled) {
                        Text(stringResource(MR.strings.pindorama_backup_creation_cancelled), Modifier.padding(16.dp))
                    }
                }

                if (DeviceUtil.isMiui && DeviceUtil.isMiuiOptimizationDisabled()) {
                    item {
                        WarningBanner(MR.strings.restore_miui_warning)
                    }
                }

                item {
                    SectionCard(MR.strings.label_library) {
                        Options(BackupOptions.libraryOptions, state, viewModel)
                    }
                }

                item {
                    SectionCard(MR.strings.label_settings) {
                        Options(BackupOptions.settingsOptions, state, viewModel)
                    }
                }
            }
        }
    }

    @Composable
    private fun Options(
        options: List<BackupOptions.Entry>,
        state: CreateBackupViewModel.State,
        model: CreateBackupViewModel,
    ) {
        options.forEach { option ->
            LabeledCheckbox(
                label = stringResource(option.label),
                checked = option.getter(state.options),
                onCheckedChange = {
                    model.toggle(option.setter, it)
                },
                enabled = option.enabled(state.options) && !state.busy,
            )
        }
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class CreateBackupViewModel(private val backupCreatorFactory: BackupCreator.Factory) : ViewModel() {

    private class Prepared(val options: BackupOptions, val password: CharArray?)
    private var prepared: Prepared? = null
    private var job: Job? = null

    val state: StateFlow<CreateBackupViewModel.State>
        field = MutableStateFlow<CreateBackupViewModel.State>(State())

    fun toggle(setter: (BackupOptions, Boolean) -> BackupOptions, enabled: Boolean) {
        if (state.value.busy) return
        state.update {
            it.copy(
                options = setter(it.options, enabled),
            )
        }
    }

    fun setProtected(enabled: Boolean) {
        if (state.value.busy) return
        discardPrepared()
        state.update { it.copy(protectedBackup = enabled, error = null, complete = false, cancelled = false) }
    }

    fun prepare(password: CharArray, confirmation: CharArray): Boolean {
        try {
            if (state.value.busy || !state.value.options.canCreate()) return false
            discardPrepared()
            if (state.value.protectedBackup) {
                try {
                    ProtectedBackupPayload.checkResources()
                } catch (_: BackupCryptoException) {
                    throw BackupException(BackupError.INSUFFICIENT_RESOURCES)
                }
            }
            val secret = if (state.value.protectedBackup) confirmedBackupPassword(password, confirmation) else null
            prepared = Prepared(state.value.options, secret)
            state.update { it.copy(waitingForDestination = true, error = null, complete = false, cancelled = false) }
            return true
        } catch (e: Exception) {
            state.update { it.copy(error = BackupError.from(e)) }
            return false
        } finally {
            password.fill('\u0000')
            confirmation.fill('\u0000')
        }
    }

    fun discardPrepared() {
        prepared?.password?.fill('\u0000')
        prepared = null
        state.update { it.copy(waitingForDestination = false) }
    }

    fun destinationFailed() {
        discardPrepared()
        state.update { it.copy(error = BackupError.IO) }
    }

    fun invalidPassword() {
        state.update { it.copy(error = BackupError.INVALID_PASSWORD) }
    }

    /** Returns true only for the existing conventional WorkManager flow. No password goes into WorkData. */
    fun createBackup(context: Context, uri: Uri): Boolean {
        val request = prepared ?: run {
            state.update { it.copy(error = BackupError.INVALID_PASSWORD) }
            return false
        }
        prepared = null
        state.update { it.copy(waitingForDestination = false) }
        if (request.password == null) {
            return try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                BackupCreateJob.startNow(context, uri, request.options)
                true
            } catch (e: Exception) {
                state.update { it.copy(error = BackupError.from(e)) }
                false
            }
        }
        state.update { it.copy(working = true, error = null, complete = false, cancelled = false) }
        val active = viewModelScope.launch(Dispatchers.IO) {
            try {
                protectedBackupOperation {
                    backupCreatorFactory.create(
                        isAutoBackup = false,
                    ).backupProtected(uri, request.options, request.password)
                }
                currentCoroutineContext().ensureActive()
                state.update { it.copy(complete = true) }
            } catch (_: CancellationException) {
                state.update { it.copy(cancelled = true) }
            } catch (e: Exception) {
                state.update { it.copy(error = BackupError.from(e)) }
            } finally {
                request.password.fill('\u0000')
            }
        }
        job = active
        // Also covers cancellation before the coroutine body ever starts, including the busy state.
        active.invokeOnCompletion { cause ->
            request.password.fill('\u0000')
            state.update {
                if (cause is CancellationException) {
                    it.copy(working = false, cancelled = true, complete = false)
                } else {
                    it.copy(working = false)
                }
            }
        }
        return false
    }

    fun cancel() {
        discardPrepared()
        job?.cancel()
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }

    @Immutable
    data class State(
        val options: BackupOptions = BackupOptions(),
        val protectedBackup: Boolean = true,
        val waitingForDestination: Boolean = false,
        val working: Boolean = false,
        val complete: Boolean = false,
        val cancelled: Boolean = false,
        val error: BackupError? = null,
    ) {
        val busy: Boolean get() = working || waitingForDestination
    }
}
