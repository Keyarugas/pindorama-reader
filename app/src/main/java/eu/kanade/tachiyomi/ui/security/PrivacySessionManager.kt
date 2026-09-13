package eu.kanade.tachiyomi.ui.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.core.security.PrivacySession
import eu.kanade.tachiyomi.core.security.PrivacySessionState
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import mihon.app.di.appGraph

/** Single session for every protected activity, initialized before activities are created. */
object PrivacySessionManager {
    private var timeoutJob: Job? = null
    lateinit var session: PrivacySession
        private set

    fun onForeground() {
        timeoutJob?.cancel()
        session.onForeground()
    }

    fun onBackground(context: Context) {
        // Device-credential authentication may open a system activity. Its background transition
        // is not an app departure; Android's prompt reports cancellation/error separately.
        if (AuthenticatorUtil.isAuthenticating && session.state.value == PrivacySessionState.AUTHENTICATING) return
        session.onBackground()
        val minutes = context.appGraph.securityPreferences.lockAppAfter.get()
        timeoutJob?.cancel()
        if (minutes > 0) {
            timeoutJob = ProcessLifecycleOwner.get().lifecycleScope.launch {
                delay(minutes * 60_000L)
                session.checkTimeout()
            }
        }
    }

    fun initialize(context: Context) {
        val preferences = context.appGraph.securityPreferences
        session = PrivacySession(preferences.useAuthenticator.get(), SystemClock::elapsedRealtime)
        session.configure(
            preferences.useAuthenticator.get(),
            preferences.lockAppAfter.get(),
            preferences.lockOnScreenOff.get(),
        )
        // Old persisted timestamps must never authorize a new process.
        preferences.lastAppClosed.delete()
        combine(
            preferences.useAuthenticator.changes(),
            preferences.lockAppAfter.changes(),
            preferences.lockOnScreenOff.changes(),
        ) { enabled, timeout, screenOff ->
            session.configure(enabled, timeout, screenOff)
        }.launchIn(ProcessLifecycleOwner.get().lifecycleScope)

        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    session.onScreenOff()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
