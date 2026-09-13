package eu.kanade.tachiyomi.ui.base.delegate

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.core.security.PrivacySessionState
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.ui.security.PrivacySessionManager
import eu.kanade.tachiyomi.ui.security.UnlockActivity
import eu.kanade.tachiyomi.util.view.setSecureScreen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import mihon.app.di.appGraph
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

interface SecureActivityDelegate {
    fun registerSecureActivity(activity: AppCompatActivity)
}

class SecureActivityDelegateImpl : SecureActivityDelegate, DefaultLifecycleObserver {
    private lateinit var activity: AppCompatActivity
    private val session get() = PrivacySessionManager.session
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        updateProtection()
        true
    }

    @Inject private lateinit var preferences: BasePreferences

    @Inject private lateinit var securityPreferences: SecurityPreferences

    override fun registerSecureActivity(activity: AppCompatActivity) {
        this.activity = activity
        Injekt.get<Context>().appGraph.inject(this)
        activity.lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        session.checkTimeout()
        updateProtection()
        activity.window.decorView.viewTreeObserver.addOnPreDrawListener(preDraw)
        combine(
            securityPreferences.secureScreen.changes(),
            preferences.incognitoMode.changes(),
            session.state,
        ) { _, _, _ -> Unit }
            .onEach {
                updateProtection()
                requestUnlock()
            }
            .launchIn(activity.lifecycleScope)
    }

    override fun onResume(owner: LifecycleOwner) {
        session.checkTimeout()
        updateProtection()
        requestUnlock()
    }

    override fun onPause(owner: LifecycleOwner) {
        updateProtection()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        activity.window.decorView.viewTreeObserver.removeOnPreDrawListener(preDraw)
    }

    private fun updateProtection() {
        val locked = session.state.value != PrivacySessionState.UNLOCKED
        val obscured = locked || (
            securityPreferences.useAuthenticator.get() &&
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            )
        val mode = securityPreferences.secureScreen.get()
        val protectScreen = mode == SecurityPreferences.SecureScreenMode.ALWAYS ||
            (mode == SecurityPreferences.SecureScreenMode.INCOGNITO && preferences.incognitoMode.get())
        activity.window.setSecureScreen(protectScreen || obscured)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Prevent a previously unlocked snapshot remaining visible after a delayed lock.
            activity.setRecentsScreenshotEnabled(!protectScreen && !securityPreferences.useAuthenticator.get())
        }
        activity.findViewById<View>(android.R.id.content)?.visibility = if (obscured) View.INVISIBLE else View.VISIBLE
    }

    private fun requestUnlock() {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val attempt = session.beginAuthentication() ?: return
        activity.startActivity(
            Intent(activity, UnlockActivity::class.java).putExtra(UnlockActivity.ATTEMPT, attempt),
        )
    }
}
