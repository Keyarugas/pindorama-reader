package eu.kanade.tachiyomi.core.security

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PrivateContentSessionTest {
    private fun PrivateContentSession.authenticate() {
        authenticationSucceeded(beginAuthentication()!!)
    }

    @Test
    fun `app lock disabled does not unlock private content`() {
        val app = PrivacySession(false) { 0L }
        val content = PrivateContentSession()
        assertEquals(PrivacySessionState.UNLOCKED, app.state.value)
        assertEquals(PrivateContentSessionState.LOCKED, content.state.value)
        content.authenticate()
        assertEquals(PrivacySessionState.UNLOCKED, app.state.value)
        assertEquals(PrivateContentSessionState.UNLOCKED, content.state.value)
    }

    @Test
    fun `global authentication does not authenticate private session`() {
        val app = PrivacySession(true) { 0L }
        val content = PrivateContentSession()
        app.authenticationSucceeded(app.beginAuthentication()!!)
        assertEquals(PrivacySessionState.UNLOCKED, app.state.value)
        assertEquals(PrivateContentSessionState.LOCKED, content.state.value)
    }

    @Test
    fun `private authentication does not authenticate global session`() {
        val app = PrivacySession(true) { 0L }
        val content = PrivateContentSession()
        content.authenticate()
        assertEquals(PrivacySessionState.LOCKED, app.state.value)
        assertEquals(PrivateContentSessionState.UNLOCKED, content.state.value)
    }

    @Test
    fun `authentication is explicit and cannot start twice`() {
        val content = PrivateContentSession()
        val attempt = content.beginAuthentication()!!
        assertEquals(PrivateContentSessionState.AUTHENTICATING, content.state.value)
        assertNull(content.beginAuthentication())
        content.authenticationSucceeded(attempt)
        assertEquals(PrivateContentSessionState.UNLOCKED, content.state.value)
        assertNull(content.beginAuthentication())
    }

    @Test
    fun `cancel or error invalidates late success`() {
        val content = PrivateContentSession()
        val attempt = content.beginAuthentication()!!
        content.authenticationCancelled(attempt)
        content.authenticationSucceeded(attempt)
        assertEquals(PrivateContentSessionState.LOCKED, content.state.value)
    }

    @Test
    fun `older callbacks cannot complete or cancel a newer attempt`() {
        val content = PrivateContentSession()
        val old = content.beginAuthentication()!!
        content.authenticationCancelled(old)
        val current = content.beginAuthentication()!!
        content.authenticationSucceeded(old)
        content.authenticationCancelled(old)
        assertEquals(PrivateContentSessionState.AUTHENTICATING, content.state.value)
        content.authenticationSucceeded(current)
        assertEquals(PrivateContentSessionState.UNLOCKED, content.state.value)
    }

    @Test
    fun `background relocks immediately independently of global never timeout`() {
        val app = PrivacySession(true) { 0L }.apply { configure(true, -1, false) }
        app.authenticationSucceeded(app.beginAuthentication()!!)
        val content = PrivateContentSession().apply { authenticate() }
        app.onBackground()
        content.onBackground()
        assertEquals(PrivacySessionState.UNLOCKED, app.state.value)
        assertEquals(PrivateContentSessionState.LOCKED, content.state.value)
    }

    @Test
    fun `background invalidates pending authentication`() {
        val content = PrivateContentSession()
        val attempt = content.beginAuthentication()!!
        content.onBackground()
        content.authenticationSucceeded(attempt)
        assertEquals(PrivateContentSessionState.LOCKED, content.state.value)
    }

    @Test
    fun `system credential activity can finish authentication`() {
        val content = PrivateContentSession()
        val attempt = content.beginAuthentication()!!
        content.onBackground(authenticationUiActive = true)
        assertEquals(PrivateContentSessionState.AUTHENTICATING, content.state.value)
        content.authenticationSucceeded(attempt)
        content.onBackground(authenticationUiActive = true)
        assertEquals(PrivateContentSessionState.LOCKED, content.state.value)
    }

    @Test
    fun `screen off relocks and invalidates credential prompt`() {
        val content = PrivateContentSession().apply { authenticate() }
        content.onScreenOff()
        assertEquals(PrivateContentSessionState.LOCKED, content.state.value)
        val attempt = content.beginAuthentication()!!
        content.onScreenOff()
        content.authenticationSucceeded(attempt)
        assertEquals(PrivateContentSessionState.LOCKED, content.state.value)
    }

    @Test
    fun `process restart starts locked`() {
        PrivateContentSession().authenticate()
        assertEquals(PrivateContentSessionState.LOCKED, PrivateContentSession().state.value)
    }

    @Test
    fun `pending destination survives cancellation and resumes after private authentication`() = runBlocking {
        val content = PrivateContentSession()
        val destination = "manga:42"
        var opened: String? = null
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            content.awaitUnlocked()
            opened = destination
        }
        content.authenticationCancelled(content.beginAuthentication()!!)
        assertNull(opened)
        content.authenticate()
        pending.join()
        assertEquals(destination, opened)
    }
}
