package tachiyomi.domain.manga.service

import eu.kanade.tachiyomi.core.security.PrivateContentSession
import eu.kanade.tachiyomi.core.security.PrivateContentSessionState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations

class MangaVisibilityPolicyTest {
    private val privateIds = setOf(2L)
    private val cover = MangaCover(2, 1, true, null, 0)

    @Test
    fun `local search hides private matches until private authentication`() = runTest {
        val matches = MutableStateFlow(listOf(2L))
        val ids = MutableStateFlow<Set<Long>?>(privateIds)
        val session = PrivateContentSession()
        val results = MangaVisibilityPolicy.observe(matches, ids, session.state) { it }
        assertEquals(emptyList<Long>(), results.first())
        session.authenticationSucceeded(session.beginAuthentication()!!)
        assertEquals(listOf(2L), results.first())
        session.lock()
        assertEquals(emptyList<Long>(), results.first())
    }

    @Test
    fun `public manga remains visible when locked`() {
        assertTrue(MangaVisibilityPolicy.isVisible(false, PrivateContentSessionState.LOCKED))
    }

    @Test
    fun `private manga is hidden when locked`() {
        assertFalse(MangaVisibilityPolicy.isVisible(true, PrivateContentSessionState.LOCKED))
    }

    @Test
    fun `private manga is visible only when unlocked`() {
        assertTrue(MangaVisibilityPolicy.isVisible(true, PrivateContentSessionState.UNLOCKED))
        assertFalse(MangaVisibilityPolicy.isVisible(true, PrivateContentSessionState.AUTHENTICATING))
    }

    @Test
    fun `library filters private records before counts and preserves categories`() {
        val items = (1L..2L).map {
            LibraryManga(Manga.create().copy(id = it, isPrivate = it == 2L), listOf(7), 5, 1, 0, 0, 0, 0)
        }
        val visible = MangaVisibilityPolicy.filter(items, privateIds, PrivateContentSessionState.LOCKED) { it.id }
        assertEquals(listOf(1L), visible.map { it.id })
        assertEquals(listOf(7L), visible.single().categories)
        assertEquals(
            2,
            MangaVisibilityPolicy.filter(items, privateIds, PrivateContentSessionState.UNLOCKED) {
                it.id
            }.size,
        )
    }

    @Test
    fun `history hides all chapters of private manga`() {
        val items = listOf(1L, 2L, 2L).map {
            HistoryWithRelations(it, it, it, "Title", 1.0, null, 0, cover)
        }
        val visible = MangaVisibilityPolicy.filter(items, privateIds, PrivateContentSessionState.LOCKED) { it.mangaId }
        assertEquals(listOf(1L), visible.map { it.mangaId })
    }

    @Test
    fun `updates hides private chapters`() {
        val items = (1L..2L).map {
            UpdatesWithRelations(it, "Title", it, "Chapter", null, "", false, false, 0, 1, 0, cover)
        }
        val visible = MangaVisibilityPolicy.filter(items, privateIds, PrivateContentSessionState.LOCKED) { it.mangaId }
        assertEquals(listOf(1L), visible.map { it.mangaId })
    }

    @Test
    fun `observable policy reacts to classification and session without exposing unloaded data`() = runTest {
        val items = MutableStateFlow(listOf(1L, 2L))
        val ids = MutableStateFlow<Set<Long>?>(null)
        val session = MutableStateFlow(PrivateContentSessionState.LOCKED)
        val visible = MangaVisibilityPolicy.observe(items, ids, session) { it }
        assertEquals(emptyList<Long>(), visible.first())
        ids.value = privateIds
        assertEquals(listOf(1L), visible.first())
        session.value = PrivateContentSessionState.UNLOCKED
        assertEquals(listOf(1L, 2L), visible.first())
        session.value = PrivateContentSessionState.AUTHENTICATING
        assertEquals(listOf(1L), visible.first())
        ids.value = emptySet()
        assertEquals(listOf(1L, 2L), visible.first())
    }

    @Test
    fun `direct private destination waits for existing session and survives cancellation`() = runTest {
        val manga = Manga.create().copy(id = 2, isPrivate = true)
        val session = PrivateContentSession()
        var opened: Long? = null
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            session.state.first { MangaVisibilityPolicy.isVisible(manga.isPrivate, it) }
            opened = manga.id
        }
        assertNull(opened)
        session.authenticationCancelled(session.beginAuthentication()!!)
        assertNull(opened)
        session.authenticationSucceeded(session.beginAuthentication()!!)
        request.join()
        assertEquals(manga.id, opened)
    }
}
