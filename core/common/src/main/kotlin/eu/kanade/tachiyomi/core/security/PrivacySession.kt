package eu.kanade.tachiyomi.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

enum class PrivacySessionState {
    LOCKED,
    AUTHENTICATING,
    UNLOCKED,
}

/** Process-local state. Call transitions on the main thread; time is monotonic milliseconds. */
class PrivacySession(
    lockEnabled: Boolean,
    private val now: () -> Long,
) {
    private var enabled = lockEnabled
    private var timeoutMinutes = 0
    private var lockOnScreenOff = false
    private var backgroundAt: Long? = null
    private var generation = 0L
    private val mutableState = MutableStateFlow(
        if (lockEnabled) PrivacySessionState.LOCKED else PrivacySessionState.UNLOCKED,
    )
    val state = mutableState.asStateFlow()

    fun configure(lockEnabled: Boolean, timeoutMinutes: Int, lockOnScreenOff: Boolean) {
        this.timeoutMinutes = timeoutMinutes
        this.lockOnScreenOff = lockOnScreenOff
        if (enabled != lockEnabled) {
            enabled = lockEnabled
            generation++
            backgroundAt = null
            mutableState.value = if (enabled) PrivacySessionState.LOCKED else PrivacySessionState.UNLOCKED
        }
    }

    fun beginAuthentication(): Long? {
        if (state.value != PrivacySessionState.LOCKED) return null
        mutableState.value = PrivacySessionState.AUTHENTICATING
        return ++generation
    }

    fun authenticationSucceeded(attempt: Long) {
        if (attempt != generation || state.value != PrivacySessionState.AUTHENTICATING) return
        backgroundAt = null
        mutableState.value = PrivacySessionState.UNLOCKED
    }

    fun authenticationCancelled(attempt: Long) {
        if (attempt == generation && state.value == PrivacySessionState.AUTHENTICATING) lock()
    }

    fun lock() {
        if (!enabled) return
        generation++
        mutableState.value = PrivacySessionState.LOCKED
    }

    fun onBackground() {
        if (backgroundAt == null) backgroundAt = now()
        if (state.value == PrivacySessionState.AUTHENTICATING || timeoutMinutes == 0) lock()
    }

    fun checkTimeout() {
        val since = backgroundAt ?: return
        if (timeoutMinutes >= 0 && now() - since >= timeoutMinutes * 60_000L) lock()
    }

    fun onForeground() {
        checkTimeout()
        backgroundAt = null
    }

    fun onScreenOff() {
        if (lockOnScreenOff || state.value == PrivacySessionState.AUTHENTICATING) lock()
    }

    suspend fun awaitUnlocked() {
        state.first { it == PrivacySessionState.UNLOCKED }
    }
}
