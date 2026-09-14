package eu.kanade.tachiyomi.ui.security

import eu.kanade.tachiyomi.core.security.PrivacySessionState
import eu.kanade.tachiyomi.core.security.PrivateContentSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The prompt is shared; authorization targets and attempt generations are independent. */
internal enum class AuthenticationTarget {
    APP,
    PRIVATE_CONTENT,
    ;

    val isLocked: Flow<Boolean>
        get() = when (this) {
            APP -> PrivacySessionManager.session.state.map { it == PrivacySessionState.LOCKED }
            PRIVATE_CONTENT -> PrivateContentSessionManager.session.state.map {
                it == PrivateContentSessionState.LOCKED
            }
        }

    val isAuthenticating: Boolean
        get() = when (this) {
            APP -> PrivacySessionManager.session.state.value == PrivacySessionState.AUTHENTICATING
            PRIVATE_CONTENT ->
                PrivateContentSessionManager.session.state.value ==
                    PrivateContentSessionState.AUTHENTICATING
        }

    val isUnlocked: Boolean
        get() = when (this) {
            APP -> PrivacySessionManager.session.state.value == PrivacySessionState.UNLOCKED
            PRIVATE_CONTENT -> PrivateContentSessionManager.session.state.value == PrivateContentSessionState.UNLOCKED
        }

    fun beginAuthentication(): Long? = when (this) {
        APP -> PrivacySessionManager.session.beginAuthentication()
        PRIVATE_CONTENT -> PrivateContentSessionManager.session.beginAuthentication()
    }

    fun authenticationSucceeded(attempt: Long) = when (this) {
        APP -> PrivacySessionManager.session.authenticationSucceeded(attempt)
        PRIVATE_CONTENT -> PrivateContentSessionManager.session.authenticationSucceeded(attempt)
    }

    fun authenticationCancelled(attempt: Long) = when (this) {
        APP -> PrivacySessionManager.session.authenticationCancelled(attempt)
        PRIVATE_CONTENT -> PrivateContentSessionManager.session.authenticationCancelled(attempt)
    }
}
