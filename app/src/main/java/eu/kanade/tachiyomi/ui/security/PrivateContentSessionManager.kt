package eu.kanade.tachiyomi.ui.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.core.security.PrivacySessionState
import eu.kanade.tachiyomi.core.security.PrivateContentSession
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil

object PrivateContentSessionManager {
    val session = PrivateContentSession()

    fun initialize(context: Context) {
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = session.onScreenOff()
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun onBackground() = session.onBackground(AuthenticatorUtil.isAuthenticating)

    /** Caller retains its own destination; only this session is authorized by the result. */
    fun authenticationIntent(context: Context): Intent? {
        if (PrivacySessionManager.session.state.value != PrivacySessionState.UNLOCKED) return null
        if (AuthenticatorUtil.isAuthenticating) return null
        val attempt = session.beginAuthentication() ?: return null
        return Intent(context, UnlockActivity::class.java)
            .putExtra(UnlockActivity.ATTEMPT, attempt)
            .putExtra(UnlockActivity.PRIVATE_CONTENT, true)
    }
}
