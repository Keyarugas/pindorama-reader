package eu.kanade.tachiyomi.core.security

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PrivacySessionTest {
    private var time = 0L
    private fun session(timeout: Int = 1, screenOff: Boolean = false) = PrivacySession(true) { time }.apply {
        configure(true, timeout, screenOff)
    }
    private fun PrivacySession.authenticate() {
        authenticationSucceeded(beginAuthentication()!!)
    }

    @Test
    fun `configured app starts locked`() {
        assertEquals(PrivacySessionState.LOCKED, session().state.value)
    }

    @Test
    fun `disabled lock starts unlocked`() {
        assertEquals(PrivacySessionState.UNLOCKED, PrivacySession(false) { time }.state.value)
    }

    @Test
    fun `authentication transitions through authenticating and succeeds`() {
        val session = session()
        val attempt = session.beginAuthentication()!!
        assertEquals(PrivacySessionState.AUTHENTICATING, session.state.value)
        assertNull(session.beginAuthentication())
        session.authenticationSucceeded(attempt)
        assertEquals(PrivacySessionState.UNLOCKED, session.state.value)
    }

    @Test
    fun `cancelled or failed authentication cannot unlock even with a late success`() {
        val session = session()
        val attempt = session.beginAuthentication()!!
        session.authenticationCancelled(attempt)
        session.authenticationSucceeded(attempt)
        assertEquals(PrivacySessionState.LOCKED, session.state.value)
    }

    @Test
    fun `short background preserves session`() {
        val session = session()
        session.authenticate()
        session.onBackground()
        time = 59_999
        session.onForeground()
        assertEquals(PrivacySessionState.UNLOCKED, session.state.value)
    }

    @Test
    fun `timeout locks at the exact boundary while backgrounded`() {
        val session = session()
        session.authenticate()
        session.onBackground()
        time = 60_000
        session.checkTimeout()
        assertEquals(PrivacySessionState.LOCKED, session.state.value)
    }

    @Test
    fun `return above timeout locks even without a timer callback`() {
        val session = session()
        session.authenticate()
        session.onBackground()
        time = 120_000
        session.onForeground()
        assertEquals(PrivacySessionState.LOCKED, session.state.value)
    }

    @Test
    fun `immediate and never timeouts keep inherited meanings`() {
        val immediate = session(timeout = 0)
        immediate.authenticate()
        immediate.onBackground()
        assertEquals(PrivacySessionState.LOCKED, immediate.state.value)
        val never = session(timeout = -1)
        never.authenticate()
        never.onBackground()
        time = Long.MAX_VALUE
        never.onForeground()
        assertEquals(PrivacySessionState.UNLOCKED, never.state.value)
    }

    @Test
    fun `screen off obeys configuration for an unlocked session`() {
        val enabled = session(screenOff = true)
        enabled.authenticate()
        enabled.onScreenOff()
        assertEquals(PrivacySessionState.LOCKED, enabled.state.value)
        val disabled = session()
        disabled.authenticate()
        disabled.onScreenOff()
        assertEquals(PrivacySessionState.UNLOCKED, disabled.state.value)
    }

    @Test
    fun `screen off invalidates an active authentication`() {
        val session = session()
        val attempt = session.beginAuthentication()!!
        session.onScreenOff()
        session.authenticationSucceeded(attempt)
        assertEquals(PrivacySessionState.LOCKED, session.state.value)
    }

    @Test
    fun `process restart never inherits unlocked state even with never timeout`() {
        session(timeout = -1).authenticate()
        assertEquals(PrivacySessionState.LOCKED, session(timeout = -1).state.value)
    }

    @Test
    fun `pending destination waits through cancelled authentication and resumes unchanged`() = runBlocking {
        val session = session()
        val requestedDestination = "manga:42/chapter:7"
        var opened: String? = null
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            session.awaitUnlocked()
            opened = requestedDestination
        }
        session.authenticationCancelled(session.beginAuthentication()!!)
        assertNull(opened)
        session.authenticate()
        pending.join()
        assertEquals(requestedDestination, opened)
    }

    @Test
    fun `background departure invalidates authentication`() {
        val session = session()
        val attempt = session.beginAuthentication()!!
        session.onBackground()
        session.authenticationSucceeded(attempt)
        assertEquals(PrivacySessionState.LOCKED, session.state.value)
    }

    @Test
    fun `stale callback cannot finish a newer attempt`() {
        val session = session()
        val first = session.beginAuthentication()!!
        session.authenticationCancelled(first)
        val second = session.beginAuthentication()!!
        session.authenticationSucceeded(first)
        assertEquals(PrivacySessionState.AUTHENTICATING, session.state.value)
        session.authenticationSucceeded(second)
        assertEquals(PrivacySessionState.UNLOCKED, session.state.value)
    }
}
