package eu.kanade.tachiyomi.ui.security

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.biometric.auth.AuthPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.isAuthenticationSupported
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.startAuthentication
import eu.kanade.tachiyomi.util.view.setSecureScreen
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/** Neutral window: a failed or cancelled prompt never reveals the activity underneath. */
class UnlockActivity : BaseActivity() {
    private val session get() = if (intent.getBooleanExtra(PRIVATE_CONTENT, false)) {
        AuthenticationTarget.PRIVATE_CONTENT
    } else {
        AuthenticationTarget.APP
    }
    private val authentication: PrivacyAuthentication by viewModels()
    private var attempt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSecureScreen(true)
        attempt = savedInstanceState?.getLong(ATTEMPT) ?: intent.getLongExtra(ATTEMPT, 0L)
        val title = if (session == AuthenticationTarget.PRIVATE_CONTENT) {
            stringResource(MR.strings.pindorama_show_private_content)
        } else {
            stringResource(MR.strings.unlock_app_title, stringResource(MR.strings.app_name))
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                addView(
                    TextView(this@UnlockActivity).apply {
                        text = stringResource(MR.strings.pindorama_unlock_info)
                    },
                )
                addView(
                    Button(this@UnlockActivity).apply {
                        text = title
                        setOnClickListener {
                            attempt = session.beginAuthentication() ?: return@setOnClickListener
                            authenticate(title)
                        }
                    },
                )
            },
        )
        onBackPressedDispatcher.addCallback(this) { closeLocked() }
        if (session.isAuthenticating && authentication.prompt == null) {
            authenticate(title)
        }
        lifecycleScope.launch {
            session.isLocked.collect {
                if (it) authentication.cancel()
            }
        }
    }

    private fun authenticate(title: String) {
        if (!isAuthenticationSupported()) {
            session.authenticationCancelled(attempt)
            return
        }
        val currentAttempt = attempt
        val currentSession = session
        val currentAuthentication = authentication
        currentAuthentication.attempt = currentAttempt
        authentication.prompt = startAuthentication(
            title,
            confirmationRequired = false,
            callback = object : AuthenticatorUtil.AuthenticationCallback() {
                override fun onAuthenticationError(
                    activity: FragmentActivity?,
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    if (currentAuthentication.attempt != currentAttempt) return
                    super.onAuthenticationError(activity, errorCode, errString)
                    currentAuthentication.prompt = null
                    currentSession.authenticationCancelled(currentAttempt)
                    // Stay on the neutral window; retry is explicit, never a lifecycle loop.
                }

                override fun onAuthenticationSucceeded(
                    activity: FragmentActivity?,
                    result: BiometricPrompt.AuthenticationResult,
                ) {
                    if (currentAuthentication.attempt != currentAttempt) return
                    super.onAuthenticationSucceeded(activity, result)
                    currentAuthentication.prompt = null
                    if (activity?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) {
                        currentSession.authenticationSucceeded(currentAttempt)
                    } else {
                        currentSession.authenticationCancelled(currentAttempt)
                    }
                    if (currentSession.isUnlocked) {
                        activity?.setResult(RESULT_OK)
                        activity?.finish()
                    }
                }
            },
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(ATTEMPT, attempt)
        super.onSaveInstanceState(outState)
    }

    private fun closeLocked() {
        session.authenticationCancelled(attempt)
        authentication.cancel()
        if (session == AuthenticationTarget.PRIVATE_CONTENT) finish() else finishAffinity()
    }

    companion object {
        const val PRIVATE_CONTENT = "private_content_authentication"
        const val ATTEMPT = "privacy_authentication_attempt"
    }
}

/** The AndroidX prompt retains its callback across rotation and supplies the current activity. */
class PrivacyAuthentication : ViewModel() {
    var attempt = 0L
    var prompt: AuthPrompt? = null

    fun cancel() {
        val active = prompt
        prompt = null
        active?.cancelAuthentication()
        if (active != null) AuthenticatorUtil.isAuthenticating = false
    }

    override fun onCleared() {
        cancel()
    }
}
