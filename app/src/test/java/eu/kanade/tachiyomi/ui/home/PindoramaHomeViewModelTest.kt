package eu.kanade.tachiyomi.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import java.util.Date

class PindoramaHomeViewModelTest {
    private val cover = MangaCover(1, 1, true, null, 0)

    @Test
    fun `empty history produces empty activity`() {
        assertEquals(PindoramaHomeViewModel.ActivitySummary(0, 0), PindoramaHomeViewModel.activity(emptyList(), 1_000_000))
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

    private fun update(mangaId: Long) = UpdatesWithRelations(mangaId, "Manga", 1, "Chapter", null, "", false, false, 0, 1, 0, cover)
}
