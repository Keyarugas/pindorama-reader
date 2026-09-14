package eu.kanade.tachiyomi.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Authorization for private works only. Never follows the global app-lock preference. */
enum class PrivateContentSessionState {
    LOCKED,
    AUTHENTICATING,
    UNLOCKED,
}

class PrivateContentSession {
    private val mutableState = MutableStateFlow(PrivateContentSessionState.LOCKED)
    val state = mutableState.asStateFlow()
    private var generation = 0L

    fun beginAuthentication(): Long? {
        if (state.value != PrivateContentSessionState.LOCKED) return null
        mutableState.value = PrivateContentSessionState.AUTHENTICATING
        return ++generation
    }

    fun authenticationSucceeded(attempt: Long) {
        if (attempt == generation && state.value == PrivateContentSessionState.AUTHENTICATING) {
            mutableState.value = PrivateContentSessionState.UNLOCKED
        }
    }

    fun authenticationCancelled(attempt: Long) {
        if (attempt == generation && state.value == PrivateContentSessionState.AUTHENTICATING) lock()
    }

    fun lock() {
        generation++
        mutableState.value = PrivateContentSessionState.LOCKED
    }

    fun onBackground(authenticationUiActive: Boolean = false) {
        // Android device credentials can temporarily take over the foreground.
        if (authenticationUiActive && state.value == PrivateContentSessionState.AUTHENTICATING) return
        lock()
    }

    fun onScreenOff() = lock()

    suspend fun awaitUnlocked() {
        state.first { it == PrivateContentSessionState.UNLOCKED }
    }
}
