package eu.kanade.tachiyomi.ui.reader

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.app.di.AppBindings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.util.Date

/** Real generated queries and SQLite JDBC; no Android driver or UI lifecycle claim. */
class ReaderWriteGuardTest {
    private val coordinator = LibraryOperationCoordinator.shared

    private class Target(val database: Database, val driver: JdbcSqliteDriver) {
        val mangas = MangaRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database)
        val histories = HistoryRepositoryImpl(database)
        val guard = ReaderWriteGuard(database, mangas, chapters)
        lateinit var manga: Manga
        lateinit var chapter: Chapter
        suspend fun seed() {
            manga =
                mangas.insertNetworkManga(
                    listOf(Manga.create().copy(source = 1, url = "/m", title = "synthetic")),
                ).single()
            assertTrue(mangas.update(MangaUpdate(manga.id, isPrivate = true)))
            manga = mangas.getMangaById(manga.id)
            chapter =
                chapters.addAll(
                    listOf(Chapter.create().copy(mangaId = manga.id, url = "/c", chapterNumber = 1.0)),
                ).single()
        }
        suspend fun current() = checkNotNull(chapters.getChapterById(chapter.id))
    }

    private suspend fun database(block: suspend (Target) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.create(driver).await()
            val target = Target(AppBindings.providesDatabase(driver), driver)
            target.seed()
            block(target)
        }
    }

    @Test
    fun `old session cannot overwrite restored progress bookmark history or viewer flags`() = runTest {
        database { t ->
            val generation = coordinator.generation
            coordinator.exclusive {
                t.chapters.update(ChapterUpdate(t.chapter.id, read = true, lastPageRead = 90, bookmark = true))
                t.mangas.update(MangaUpdate(t.manga.id, viewerFlags = 123))
            }
            val before = t.current()
            assertThrows<ReaderWriteGuard.StaleState> {
                t.guard.progress(generation, t.manga, t.chapter, 2, false, false)
            }
            assertThrows<ReaderWriteGuard.StaleState> { t.guard.bookmark(generation, t.manga, t.chapter) }
            assertThrows<ReaderWriteGuard.StaleState> {
                t.guard.history(generation, t.manga, t.chapter, HistoryUpdate(t.chapter.id, Date(100), 10))
            }
            assertThrows<ReaderWriteGuard.StaleState> { t.guard.viewerFlag(generation, t.manga, 0, 255) }
            assertEquals(before, t.current())
            assertTrue(t.histories.getHistoryByMangaId(t.manga.id).isEmpty())
            assertEquals(123L, t.mangas.getMangaById(t.manga.id).viewerFlags)
            assertTrue(t.mangas.getMangaById(t.manga.id).isPrivate)
            // A newly opened reader can save again; old sessions are not silently rebased.
            t.guard.progress(coordinator.generation, t.manga, t.current(), 3, false, false)
            assertTrue(t.current().read)
        }
    }

    @Test
    fun `normal reading uses current read state and toggles current bookmark without writing old snapshot`() = runTest {
        database { t ->
            val generation = coordinator.generation
            t.chapters.update(ChapterUpdate(t.chapter.id, read = true, bookmark = true))
            val saved = t.guard.progress(generation, t.manga, t.chapter, 5, false, false)
            assertTrue(saved.read)
            assertTrue(t.current().bookmark)
            assertEquals(5L, t.current().lastPageRead)
            assertFalse(t.guard.bookmark(generation, t.manga, t.chapter))
            t.guard.history(generation, t.manga, t.chapter, HistoryUpdate(t.chapter.id, Date(100), 10))
            assertEquals(1, t.histories.getHistoryByMangaId(t.manga.id).size)
        }
    }

    @Test
    fun `changed manga source url chapter url parent or missing chapter rejects write at execution`() = runTest {
        for (change in 0..4) {
            database { t ->
                val generation = coordinator.generation
                when (change) {
                    0 -> t.mangas.update(MangaUpdate(t.manga.id, source = 2))
                    1 -> t.mangas.update(MangaUpdate(t.manga.id, url = "/changed"))
                    2 -> t.chapters.update(ChapterUpdate(t.chapter.id, url = "/changed"))
                    3 -> {
                        val other = t.mangas.insertNetworkManga(
                            listOf(Manga.create().copy(source = 1, url = "/other")),
                        ).single()
                        t.chapters.update(ChapterUpdate(t.chapter.id, mangaId = other.id))
                    }
                    4 -> t.chapters.removeChaptersWithIds(listOf(t.chapter.id))
                }
                val before = t.chapters.getChapterById(t.chapter.id)
                assertThrows<ReaderWriteGuard.StaleState> {
                    t.guard.progress(generation, t.manga, t.chapter, 8, true, true)
                }
                assertEquals(before, t.chapters.getChapterById(t.chapter.id))
            }
        }
    }

    @Test
    fun `callback queued during exclusive mutation validates only after admission`() = runTest {
        database { t ->
            val old = coordinator.generation
            val finish = CompletableDeferred<Unit>()
            val exclusive = launch { coordinator.exclusive { finish.await() } }
            runCurrent()
            val write = async {
                assertThrows<ReaderWriteGuard.StaleState> { t.guard.progress(old, t.manga, t.chapter, 6, true, false) }
            }
            runCurrent()
            assertFalse(write.isCompleted)
            finish.complete(Unit)
            exclusive.join()
            write.await()
            assertFalse(t.current().read)
        }
    }

    @Test
    fun `cancel waiting reader leaves database untouched and admission reusable`() = runTest {
        database { t ->
            val generation = coordinator.generation
            val finish = CompletableDeferred<Unit>()
            val exclusive = launch { coordinator.exclusive { finish.await() } }
            runCurrent()
            val write = launch { t.guard.progress(generation, t.manga, t.chapter, 6, true, false) }
            runCurrent()
            write.cancelAndJoin()
            finish.complete(Unit)
            exclusive.join()
            assertFalse(t.current().read)
            t.guard.progress(coordinator.generation, t.manga, t.chapter, 1, false, false)
            assertEquals(1L, t.current().lastPageRead)
        }
    }

    @Test
    fun `failed SQL write is not acknowledged and transaction preserves previous progress`() = runTest {
        database { t ->
            t.driver.execute(
                null,
                "CREATE TRIGGER reject_progress BEFORE UPDATE ON chapters BEGIN SELECT RAISE(ABORT, 'synthetic'); END",
                0,
            )
            val before = t.current()
            assertThrows<Exception> {
                t.guard.progress(coordinator.generation, t.manga, t.chapter, 6, true, true)
            }
            assertEquals(before, t.current())
            coordinator.exclusive { }
        }
    }

    @Test
    fun `history SQL failure propagates instead of being treated as saved`() = runTest {
        database { t ->
            t.driver.execute(
                null,
                "CREATE TRIGGER reject_history BEFORE INSERT ON history BEGIN SELECT RAISE(ABORT, 'synthetic'); END",
                0,
            )
            assertThrows<Exception> {
                t.guard.history(coordinator.generation, t.manga, t.chapter, HistoryUpdate(t.chapter.id, Date(100), 10))
            }
            assertTrue(t.histories.getHistoryByMangaId(t.manga.id).isEmpty())
        }
    }

    @Test
    fun `reader network effect owns admission until response and cannot start from old session`() = runTest {
        database { t ->
            val generation = coordinator.generation
            val response = CompletableDeferred<Unit>()
            var applied = false
            val effect = launch {
                t.guard.chapterEffect(generation, t.manga, t.chapter) { _, _ ->
                    response.await()
                    applied = true
                }
            }
            runCurrent()
            var exclusiveEntered = false
            val exclusive = launch { coordinator.exclusive { exclusiveEntered = true } }
            runCurrent()
            assertFalse(exclusiveEntered)
            response.complete(Unit)
            effect.join()
            exclusive.join()
            assertTrue(applied)
            assertThrows<ReaderWriteGuard.StaleState> {
                t.guard.chapterEffect(generation, t.manga, t.chapter) { _, _ -> error("must not execute") }
            }
        }
    }
}
