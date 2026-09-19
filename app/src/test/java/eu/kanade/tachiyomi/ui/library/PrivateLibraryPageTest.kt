package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.core.security.PrivateContentSessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class PrivateLibraryPageTest {
    private val romance = Category(1, "Romance", 0, 0)
    private val fiction = Category(2, "Fiction", 1, 0)
    private val public = item(1, false)
    private val private = item(2, true)

    private fun state(session: PrivateContentSessionState) = LibraryViewModel.State(
        privateSession = session,
        showMangaCount = true,
        libraryData = LibraryViewModel.LibraryData(favorites = listOf(public, private)),
        groupedFavorites = linkedMapOf(romance to listOf(1, 2), fiction to listOf(2)),
    )

    @Test
    fun `locked has no private page count or contents`() {
        val state = state(PrivateContentSessionState.LOCKED)
        assertFalse(LibraryPage.Private in state.pages)
        assertNull(state.getItemCountForPage(LibraryPage.Private))
        assertTrue(state.getItemsForPage(LibraryPage.Private).isEmpty())
        assertEquals(1, state.getItemCountForCategory(romance))
    }

    @Test
    fun `authenticating does not reveal private page`() {
        val state = state(PrivateContentSessionState.AUTHENTICATING)
        assertFalse(LibraryPage.Private in state.pages)
        assertNull(state.getItemCountForPage(LibraryPage.Private))
        assertTrue(state.getItemsForPage(LibraryPage.Private).isEmpty())
    }

    @Test
    fun `unlocked appends derived page with only distinct private works`() {
        val state = state(PrivateContentSessionState.UNLOCKED)
        assertEquals(listOf(LibraryPage.Normal(romance), LibraryPage.Normal(fiction), LibraryPage.Private), state.pages)
        assertEquals(listOf(2L), state.getItemsForPage(LibraryPage.Private).map { it.id })
        assertEquals(1, state.getItemCountForPage(LibraryPage.Private))
    }

    @Test
    fun `private work remains in normal categories without changing associations`() {
        val state = state(PrivateContentSessionState.UNLOCKED)
        assertEquals(listOf(1L, 2L), state.getItemsForPage(LibraryPage.Normal(romance)).map { it.id })
        assertEquals(listOf(2L), state.getItemsForPage(LibraryPage.Normal(fiction)).map { it.id })
        assertEquals(listOf(1L, 2L), private.libraryManga.categories)
        assertEquals(listOf(romance, fiction), state.displayedCategories)
    }

    @Test
    fun `relock from private page returns to last valid normal category`() {
        val unlocked = state(PrivateContentSessionState.UNLOCKED).copy(
            activeCategoryIndex = 1,
            privatePageSelected = true,
        )
        assertEquals(LibraryPage.Private, unlocked.activePage)
        val locked = unlocked.copy(privateSession = PrivateContentSessionState.LOCKED)
        assertEquals(LibraryPage.Normal(fiction), locked.activePage)
        assertTrue(locked.getItemsForPage(locked.activePage).isEmpty())
        assertEquals(1, locked.activePageIndex)
        val title = locked.getToolbarTitle("Library", "Default", locked.activePageIndex, "Private")
        assertFalse(title.toString().contains("Private"))
    }

    @Test
    fun `zero private works still has stable empty private page when unlocked`() {
        val state = state(PrivateContentSessionState.UNLOCKED).copy(
            privatePageSelected = true,
            libraryData = LibraryViewModel.LibraryData(favorites = listOf(public)),
        )
        assertEquals(LibraryPage.Private, state.activePage)
        assertEquals(0, state.getItemCountForPage(LibraryPage.Private))
        assertTrue(state.getItemsForPage(LibraryPage.Private).isEmpty())
    }

    @Test
    fun `empty category list handles private page and relock without invalid index`() {
        val unlocked = LibraryViewModel.State(
            privateSession = PrivateContentSessionState.UNLOCKED,
            privatePageSelected = true,
        )
        assertEquals(listOf(LibraryPage.Private), unlocked.pages)
        assertEquals(0, unlocked.activePageIndex)
        val locked = unlocked.copy(privateSession = PrivateContentSessionState.LOCKED)
        assertTrue(locked.pages.isEmpty())
        assertNull(locked.activePage)
        assertEquals(0, locked.activePageIndex)
    }

    @Test
    fun `restored out of range index and stale private selection cannot reopen locked page`() {
        val locked = state(PrivateContentSessionState.LOCKED).copy(
            activeCategoryIndex = 99,
            privatePageSelected = true,
        )
        assertEquals(LibraryPage.Normal(fiction), locked.activePage)
        assertNull(locked.getItemCountForPage(LibraryPage.Private))
        assertEquals(listOf(romance, fiction), locked.displayedCategories)
    }

    @Test
    fun `default restored state never selects private page even when unlocked`() {
        val state = state(PrivateContentSessionState.UNLOCKED).copy(activeCategoryIndex = 99)
        assertEquals(LibraryPage.Normal(fiction), state.activePage)
    }

    private fun item(id: Long, isPrivate: Boolean): LibraryItem {
        val manga = Manga.create().copy(id = id, title = "Work $id", isPrivate = isPrivate)
        return LibraryItem(
            libraryManga = LibraryManga(manga, listOf(1, 2), 0, 0, 0, 0, 0, 0),
            downloadCount = 0,
            unreadCount = 0,
            isLocal = false,
            sourceName = "Source",
            sourceLanguage = "en",
            badges = LibraryItem.Badges(0, 0, false, "en"),
        )
    }
}
