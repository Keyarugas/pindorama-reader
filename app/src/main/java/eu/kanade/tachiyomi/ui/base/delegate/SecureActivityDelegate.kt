package eu.kanade.tachiyomi.ui.base.delegate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.core.security.PrivacySessionState
import eu.kanade.tachiyomi.core.security.PrivateContentSessionState
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.security.PrivacySessionManager
import eu.kanade.tachiyomi.ui.security.PrivateContentSessionManager
import eu.kanade.tachiyomi.ui.security.UnlockActivity
import eu.kanade.tachiyomi.util.view.setSecureScreen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import mihon.app.di.appGraph
import tachiyomi.core.common.Constants
import tachiyomi.domain.manga.service.MangaVisibilityPolicy
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

interface SecureActivityDelegate {
    fun registerSecureActivity(activity: AppCompatActivity)
}

class SecureActivityDelegateImpl : SecureActivityDelegate, DefaultLifecycleObserver {
    private lateinit var activity: AppCompatActivity
    private val session get() = PrivacySessionManager.session
    private val privateSession get() = PrivateContentSessionManager.session
    private val privateIds get() = activity.appGraph.privateContentVisibility.privateIds.value
    private var privateRequestSent = false
    private lateinit var privateAuthentication: ActivityResultLauncher<Intent>
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        updateProtection()
        true
    }

    @Inject private lateinit var preferences: BasePreferences

    @Inject private lateinit var securityPreferences: SecurityPreferences

    override fun registerSecureActivity(activity: AppCompatActivity) {
        this.activity = activity
        privateAuthentication = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode != Activity.RESULT_OK && privateReaderBlocked()) activity.finish()
        }
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
            privateSession.state,
            activity.appGraph.privateContentVisibility.privateIds,
        ) { _, _, _, _, _ -> Unit }
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
        val obscured = locked || privateReaderBlocked() || (
            (securityPreferences.useAuthenticator.get() || privateIds?.isNotEmpty() == true) &&
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            )
        val mode = securityPreferences.secureScreen.get()
        val protectScreen = mode == SecurityPreferences.SecureScreenMode.ALWAYS ||
            (mode == SecurityPreferences.SecureScreenMode.INCOGNITO && preferences.incognitoMode.get())
        activity.window.setSecureScreen(protectScreen || obscured)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Prevent a previously unlocked snapshot remaining visible after a delayed lock.
            activity.setRecentsScreenshotEnabled(
                !protectScreen && !securityPreferences.useAuthenticator.get() && privateIds?.isEmpty() == true,
            )
        }
        activity.findViewById<View>(android.R.id.content)?.visibility = if (obscured) View.INVISIBLE else View.VISIBLE
    }

    private fun privateReaderBlocked(): Boolean {
        // Keep Reader implementation untouched. Its existing intent supplies the originating manga.
        if (activity !is ReaderActivity) return false
        val ids = privateIds ?: return true
        val mangaId = activity.intent.getLongExtra(Constants.MANGA_EXTRA, -1L)
        return !MangaVisibilityPolicy.isVisible(
            if (mangaId == -1L) ids.isNotEmpty() else mangaId in ids,
            privateSession.state.value,
        )
    }

    private fun requestUnlock() {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (privateSession.state.value == PrivateContentSessionState.UNLOCKED) privateRequestSent = false
        if (session.state.value == PrivacySessionState.UNLOCKED && privateReaderBlocked() && privateIds != null) {
            if (!privateRequestSent) {
                PrivateContentSessionManager.authenticationIntent(activity)?.let {
                    privateRequestSent = true
                    privateAuthentication.launch(it)
                }
            }
            return
        }
        val attempt = session.beginAuthentication() ?: return
        activity.startActivity(
            Intent(activity, UnlockActivity::class.java).putExtra(UnlockActivity.ATTEMPT, attempt),
        )
    }
}
