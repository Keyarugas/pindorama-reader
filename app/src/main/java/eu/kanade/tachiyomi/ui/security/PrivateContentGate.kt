package eu.kanade.tachiyomi.ui.security

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import eu.kanade.tachiyomi.core.security.PrivacySessionState
import kotlinx.coroutines.flow.first
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** No identifying data is composed here. A cancelled request requires an explicit retry. */
@Composable
fun PrivateContentGate(destinationKey: Long, autoRequest: Boolean = true, onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var requested by rememberSaveable(destinationKey) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val request = {
        PrivateContentSessionManager.authenticationIntent(context)?.let {
            requested = true
            launcher.launch(it)
        }
    }
    LaunchedEffect(destinationKey, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            PrivacySessionManager.session.state.first { it == PrivacySessionState.UNLOCKED }
            if (autoRequest && !requested) request()
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(MR.strings.pindorama_private_content_locked))
        Button(onClick = { request() }) { Text(stringResource(MR.strings.pindorama_show_private_content)) }
        TextButton(onClick = onBack) { Text(stringResource(MR.strings.action_cancel)) }
    }
}
