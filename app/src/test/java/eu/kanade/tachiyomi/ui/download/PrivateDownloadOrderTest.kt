package eu.kanade.tachiyomi.ui.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrivateDownloadOrderTest {
    @Test
    fun `reordering public downloads preserves hidden rows and positions`() {
        assertEquals(
            listOf(3L, 2L, 1L, 4L),
            preserveHiddenDownloadOrder(listOf(1L, 2L, 3L, 4L), listOf(3L, 1L)) { it },
        )
    }

    @Test
    fun `empty visible queue does not delete hidden downloads`() {
        assertEquals(listOf(2L), preserveHiddenDownloadOrder(listOf(2L), emptyList()) { it })
    }

    @Test
    fun `stale or duplicate dragged rows cannot add or remove downloads`() {
        assertEquals(
            listOf(3L, 2L, 1L),
            preserveHiddenDownloadOrder(listOf(1L, 2L, 3L), listOf(3L, 3L, 99L, 1L)) { it },
        )
    }
}
