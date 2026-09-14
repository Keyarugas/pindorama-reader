package eu.kanade.tachiyomi.ui.home

import eu.kanade.tachiyomi.core.security.PrivateContentSessionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.service.MangaVisibilityPolicy
import tachiyomi.domain.updates.model.UpdatesWithRelations
import java.util.Date
import kotlin.time.Duration.Companion.days

class PindoramaHomeViewModelTest {
    private val cover = MangaCover(1, 1, true, null, 0)

    @Test
    fun `empty history produces empty activity`() {
        assertEquals(null, PindoramaHomeViewModel.selectContinueReading(emptyList()))
        assertEquals(
            PindoramaHomeViewModel.ActivitySummary(0, 0),
            PindoramaHomeViewModel.activity(emptyList(), 1_000_000),
        )
    }

    @Test
    fun `continue reading selects the most recent history item`() {
        val items = listOf(history(2, 2_000), history(1, 1_000))
        assertEquals(2L, PindoramaHomeViewModel.selectContinueReading(items)?.mangaId)
    }

    @Test
    fun `activity counts recent chapters and distinct works`() {
        val now = 10_000_000L
        val history = listOf(
            history(1, now - 1_000),
            history(1, now - 2_000),
            history(2, now - 8.days.inWholeMilliseconds),
        )
        assertEquals(PindoramaHomeViewModel.ActivitySummary(2, 1), PindoramaHomeViewModel.activity(history, now))
    }

    @Test
    fun `updates are grouped and limited`() {
        val items = (1L..7L).flatMap { mangaId ->
            listOf(update(mangaId), update(mangaId))
        }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), PindoramaHomeViewModel.recentUpdates(items).map { it.mangaId })
    }

    @Test
    fun `home selection updates and metrics follow private session`() {
        val now = 10_000_000L
        PrivateContentSessionState.entries.forEach { session ->
            val history = MangaVisibilityPolicy.filter(
                listOf(history(2, now), history(1, now - 1_000)),
                setOf(2L),
                session,
            ) { it.mangaId }
            val updates = MangaVisibilityPolicy.filter(
                listOf(update(2), update(1)),
                setOf(2L),
                session,
            ) { it.mangaId }
            val unlocked = session == PrivateContentSessionState.UNLOCKED
            assertEquals(if (unlocked) 2L else 1L, PindoramaHomeViewModel.selectContinueReading(history)?.mangaId)
            val count = if (unlocked) 2 else 1
            assertEquals(
                PindoramaHomeViewModel.ActivitySummary(count, count),
                PindoramaHomeViewModel.activity(history, now),
            )
            assertEquals(
                if (unlocked) listOf(2L, 1L) else listOf(1L),
                PindoramaHomeViewModel.recentUpdates(updates).map { it.mangaId },
            )
        }
    }

    private fun history(mangaId: Long, readAt: Long) = HistoryWithRelations(
        id = mangaId,
        chapterId = mangaId,
        mangaId = mangaId,
        title = "Manga $mangaId",
        chapterNumber = 1.0,
        readAt = Date(readAt),
        readDuration = 0,
        coverData = cover,
    )

    private fun update(mangaId: Long) =
        UpdatesWithRelations(mangaId, "Manga", 1, "Chapter", null, "", false, false, 0, 1, 0, cover)
}
