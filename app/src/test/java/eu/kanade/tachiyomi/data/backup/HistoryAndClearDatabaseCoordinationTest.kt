package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.ClearDatabaseEntries
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.app.di.AppBindings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.util.Date

/** Real SQLite, synthetic restored state and barriers at actual read/write boundaries. */
class HistoryAndClearDatabaseCoordinationTest {
    private val coordinator = LibraryOperationCoordinator.shared

    private class Driver(private val delegate: SqlDriver) : SqlDriver by delegate {
        var onSql: suspend (String) -> Unit = {}
        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> = QueryResult.AsyncValue {
            onSql(sql)
            delegate.execute(identifier, sql, parameters, binders).await()
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> = QueryResult.AsyncValue {
            onSql(sql)
            delegate.executeQuery(identifier, sql, mapper, parameters, binders).await()
        }
    }

    private class Target(val database: Database, val driver: Driver) {
        val mangas = MangaRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database)
        val history = HistoryRepositoryImpl(database)
        val remove = RemoveHistory(history, mangas, chapters, GetManga(mangas))
        val clear = ClearDatabaseEntries(database)
        lateinit var entries: List<Manga>
        lateinit var chapterList: List<Chapter>
        val manga get() = entries[0]
        val chapter get() = chapterList[0]

        suspend fun seed() {
            entries = mangas.insertNetworkManga(
                (0..4).map {
                    Manga.create().copy(
                        source = if (it == 4) 2 else 1,
                        url = "/manga-$it",
                        title = "synthetic $it",
                        favorite = it == 3,
                        notes = "retained $it",
                    )
                },
            )
            chapterList = chapters.addAll(
                entries.mapIndexed { index, manga ->
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/chapter-$index",
                        name = "synthetic chapter $index",
                        read = index == 1,
                        lastPageRead = if (index == 2) 7 else 0,
                        bookmark = true,
                    )
                },
            )
            chapterList.forEach { history.upsertHistory(HistoryUpdate(it.id, Date(1234), 67)) }
        }

        suspend fun target(): RemoveHistory.Target {
            val row = history.getHistory("").first().single { it.mangaId == manga.id }
            return requireNotNull(remove.loadTarget(row, LibraryOperationCoordinator.shared.generation))
        }

        suspend fun histories() = entries.flatMap { history.getHistoryByMangaId(it.id) }

        suspend fun exists(index: Int): Boolean =
            mangas.getMangaByUrlAndSourceId(entries[index].url, entries[index].source) != null
    }

    private suspend fun database(block: suspend (Target) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { jdbc ->
            jdbc.execute(null, "PRAGMA foreign_keys = ON", 0).await()
            Database.Schema.create(jdbc).await()
            val driver = Driver(jdbc)
            val target = Target(AppBindings.providesDatabase(driver), driver)
            target.seed()
            block(target)
        }
        // Detect any participant/exclusive left behind by completed or cancelled work.
        coordinator.exclusive { }
    }

    private suspend fun exclusiveWaits(
        target: Target,
        phase: String,
        operation: suspend () -> Boolean,
    ) = coroutineScope {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        target.driver.onSql = { sql ->
            if (sql.trimStart().startsWith(phase)) {
                reached.complete(Unit)
                release.await()
            }
        }
        val generation = coordinator.generation
        val work = async(start = CoroutineStart.UNDISPATCHED) { operation() }
        reached.await()
        var entered = false
        val restore = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
        assertFalse(entered)
        assertEquals(generation, coordinator.generation)
        release.complete(Unit)
        assertTrue(work.await())
        restore.join()
        assertTrue(entered)
        target.driver.onSql = {}
    }

    @Test
    fun `stale single confirmation performs no reads or writes`() = runBlocking {
        database { t ->
            val target = t.target()
            coordinator.exclusive { t.history.upsertHistory(HistoryUpdate(t.chapter.id, Date(5678), 3)) }
            val restored = t.histories()
            var sql = 0
            t.driver.onSql = { sql++ }
            assertFalse(t.remove.await(target))
            assertEquals(0, sql)
            t.driver.onSql = {}
            assertEquals(restored, t.histories())
        }
    }

    @Test
    fun `fresh single resets only last read and preserves duration manga and chapter`() = runBlocking {
        database { t ->
            val target = t.target()
            val before = t.histories()
            val manga = t.mangas.getMangaById(t.manga.id)
            val chapter = t.chapters.getChapterById(t.chapter.id)
            assertTrue(t.remove.await(target))
            assertEquals(
                before.map { if (it.id == target.historyId) it.copy(readAt = Date(0)) else it },
                t.histories(),
            )
            assertEquals(manga, t.mangas.getMangaById(t.manga.id))
            assertEquals(chapter, t.chapters.getChapterById(t.chapter.id))
        }
    }

    @Test
    fun `single confirmation rejects manga source url id and chapter identity mismatches`() = runBlocking {
        database { t ->
            val target = t.target()
            val before = t.histories()
            for (expected in listOf(
                target.manga.copy(id = target.manga.id + 100),
                target.manga.copy(source = 999),
                target.manga.copy(url = "/other-identity"),
            )) {
                assertFalse(t.remove.await(target.copy(manga = expected)))
            }
            for (expected in listOf(
                target.chapter.copy(id = target.chapter.id + 100),
                target.chapter.copy(mangaId = t.entries[1].id),
                target.chapter.copy(url = "/other-chapter"),
            )) {
                assertFalse(t.remove.await(target.copy(chapter = expected)))
            }
            assertFalse(t.remove.await(target.copy(historyId = before[1].id)))
            t.chapters.updateAll(listOf(ChapterUpdate(t.chapter.id, url = "/replaced")))
            assertFalse(t.remove.await(target))
            assertEquals(before, t.histories())
        }
    }

    @Test
    fun `missing history and chapter are ignored`() = runBlocking {
        database { t ->
            val target = t.target()
            t.database.historyQueries.removeAllHistory()
            assertFalse(t.remove.await(target))
            t.history.upsertHistory(HistoryUpdate(t.chapter.id, Date(1234), 67))
            t.database.chaptersQueries.removeChaptersWithIds(listOf(t.chapter.id))
            assertFalse(t.remove.await(target))
        }
    }

    @Test
    fun `manga disappearing between history validation and lookup returns no target`() = runBlocking {
        database { t ->
            val row = t.history.getHistory("").first().single { it.mangaId == t.manga.id }
            val before = t.histories().filterNot { it.chapterId == t.chapter.id }
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var historyWrites = 0
            t.driver.onSql = { sql ->
                if (sql.trimStart().startsWith("UPDATE history") ||
                    sql.trimStart().startsWith("DELETE FROM history")
                ) {
                    historyWrites++
                }
                if (sql.contains("FROM mangas\nWHERE _id =")) {
                    // loadTarget already read the chapter and validated its history association.
                    reached.complete(Unit)
                    release.await()
                }
            }
            val generation = coordinator.generation
            val lookup = async(start = CoroutineStart.UNDISPATCHED) { t.remove.loadTarget(row, generation) }
            reached.await()
            coordinator.operation {
                t.driver.execute(null, "DELETE FROM mangas WHERE _id = ?", 1) {
                    bindLong(0, t.manga.id)
                }.await()
            }
            assertNull(t.chapters.getChapterById(t.chapter.id))
            assertTrue(t.history.getHistoryByMangaId(t.manga.id).isEmpty())
            release.complete(Unit)
            assertNull(lookup.await())
            assertEquals(0, historyWrites)
            assertEquals(generation, coordinator.generation)
            t.driver.onSql = {}
            assertFalse(t.exists(0))
            assertEquals(before, t.histories())
        }
    }

    @Test
    fun `loading confirmation checks generation before resolving identities`() = runBlocking {
        database { t ->
            val row = t.history.getHistory("").first().single { it.mangaId == t.manga.id }
            val generation = coordinator.generation
            coordinator.exclusive { }
            var sql = 0
            t.driver.onSql = { sql++ }
            assertNull(t.remove.loadTarget(row, generation))
            assertEquals(0, sql)
        }
    }

    @Test
    fun `single removal holds lease during requery and final reset`() = runBlocking {
        database { t ->
            for (phase in listOf("SELECT", "UPDATE history")) {
                t.history.upsertHistory(HistoryUpdate(t.chapter.id, Date(1234), 0))
                val target = t.target()
                exclusiveWaits(t, phase) { t.remove.await(target) }
            }
        }
    }

    @Test
    fun `manga history removal discards stale confirmation and revalidates identity`() = runBlocking {
        database { t ->
            val additional = t.chapters.addAll(
                listOf(Chapter.create().copy(mangaId = t.manga.id, url = "/additional", name = "synthetic")),
            ).single()
            t.history.upsertHistory(HistoryUpdate(additional.id, Date(1234), 11))
            val generation = coordinator.generation
            coordinator.exclusive { t.history.upsertHistory(HistoryUpdate(t.chapter.id, Date(5678), 3)) }
            val before = t.histories()
            var sql = 0
            t.driver.onSql = { sql++ }
            assertFalse(t.remove.await(t.manga, generation))
            assertEquals(0, sql)
            t.driver.onSql = {}
            assertFalse(t.remove.await(t.manga.copy(source = 999), coordinator.generation))
            assertFalse(t.remove.await(t.manga.copy(url = "/changed"), coordinator.generation))
            assertFalse(t.remove.await(t.manga.copy(id = 999), coordinator.generation))
            assertEquals(before, t.histories())
            assertTrue(t.remove.await(t.manga, coordinator.generation))
            assertEquals(
                before.map {
                    if (it.chapterId in listOf(t.chapter.id, additional.id)) it.copy(readAt = Date(0)) else it
                },
                t.histories(),
            )
        }
    }

    @Test
    fun `clear all discards stale dialog without any sql`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            coordinator.exclusive { }
            val before = t.histories()
            var sql = 0
            t.driver.onSql = { sql++ }
            assertFalse(t.remove.awaitAll(generation))
            assertEquals(0, sql)
            t.driver.onSql = {}
            assertEquals(before, t.histories())
        }
    }

    @Test
    fun `clear all uses one lease through global delete`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            exclusiveWaits(t, "DELETE FROM history") { t.remove.awaitAll(generation) }
            assertTrue(t.histories().isEmpty())
        }
    }

    @Test
    fun `clear database stale confirmation does not delete or clean history`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            coordinator.exclusive {
                t.history.upsertHistory(HistoryUpdate(t.chapter.id, Date(5678), 3))
                t.history.resetHistoryByMangaId(t.entries[4].id)
            }
            val before = t.histories()
            var sql = 0
            t.driver.onSql = { sql++ }
            assertFalse(t.clear.await(listOf(1), false, generation))
            assertEquals(0, sql)
            t.driver.onSql = {}
            assertEquals(before, t.histories())
            for (i in t.entries.indices) assertTrue(t.exists(i))
        }
    }

    @Test
    fun `clear database honors current favorite and keep read or progress`() = runBlocking {
        database { t ->
            t.mangas.update(MangaUpdate(t.manga.id, favorite = true))
            assertTrue(t.clear.await(listOf(1), true, coordinator.generation))
            for (i in t.entries.indices) assertTrue(t.exists(i))
            t.mangas.update(MangaUpdate(t.manga.id, favorite = false))
            assertTrue(t.clear.await(listOf(1), true, coordinator.generation))
            assertFalse(t.exists(0))
            for (i in 1..4) assertTrue(t.exists(i))
        }
    }

    @Test
    fun `clear database without keep read cascades and cleans zero history`() = runBlocking {
        database { t ->
            t.history.resetHistoryByMangaId(t.entries[4].id)
            assertTrue(t.clear.await(listOf(1), false, coordinator.generation))
            for (i in 0..2) {
                assertFalse(t.exists(i))
                assertNull(t.chapters.getChapterById(t.chapterList[i].id))
                assertTrue(t.history.getHistoryByMangaId(t.entries[i].id).isEmpty())
            }
            assertTrue(t.exists(3))
            assertTrue(t.exists(4))
            assertEquals(listOf(t.chapterList[3].id), t.histories().map { it.chapterId })
        }
    }

    @Test
    fun `clear database keeps one lease through manga delete and history cleanup`() = runBlocking {
        database { t ->
            for (phase in listOf("DELETE FROM mangas", "DELETE FROM history")) {
                val generation = coordinator.generation
                exclusiveWaits(t, phase) { t.clear.await(listOf(1), true, generation) }
            }
        }
    }

    @Test
    fun `cancelled history removal waiting admission never reads or writes`() = runBlocking {
        database { t ->
            val target = t.target()
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val restore = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            var sql = 0
            t.driver.onSql = { sql++ }
            val removal = launch(start = CoroutineStart.UNDISPATCHED) { t.remove.await(target) }
            removal.cancelAndJoin()
            assertEquals(0, sql)
            release.complete(Unit)
            restore.join()
        }
    }

    @Test
    fun `history cancellation during requery or before first write does not continue`() = runBlocking {
        database { t ->
            for (phase in listOf("SELECT", "UPDATE history")) {
                val target = t.target()
                val before = t.histories()
                val reached = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                t.driver.onSql = {
                    if (it.trimStart().startsWith(phase)) {
                        reached.complete(Unit)
                        release.await()
                    }
                }
                val removal = launch(start = CoroutineStart.UNDISPATCHED) { t.remove.await(target) }
                reached.await()
                removal.cancelAndJoin()
                t.driver.onSql = {}
                assertEquals(before, t.histories())
                coordinator.exclusive { }
            }
        }
    }

    @Test
    fun `cancelled drain without generation change lets pending history intent proceed`() = runBlocking {
        database { t ->
            val target = t.target()
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val active = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.operation {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            var applied = false
            val restore = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { applied = true } }
            val removal = async(start = CoroutineStart.UNDISPATCHED) { t.remove.await(target) }
            assertFalse(removal.isCompleted)
            restore.cancelAndJoin()
            assertFalse(applied)
            assertEquals(target.generation, coordinator.generation)
            assertTrue(removal.await())
            release.complete(Unit)
            active.join()
        }
    }

    @Test
    fun `clear database non cancellable maintenance holds lease through real cleanup`() = runBlocking {
        database { t ->
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            t.driver.onSql = {
                if (it.trimStart().startsWith("DELETE FROM history")) {
                    reached.complete(Unit)
                    release.await()
                }
            }
            val generation = coordinator.generation
            val work = launch(start = CoroutineStart.UNDISPATCHED) { t.clear.await(listOf(1), false, generation) }
            reached.await()
            work.cancel()
            assertFalse(work.isCompleted)
            var entered = false
            val restore = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
            assertFalse(entered)
            release.complete(Unit)
            work.join()
            restore.join()
            assertTrue(entered)
            t.driver.onSql = {}
            assertFalse(t.exists(0))
        }
    }

    @Test
    fun `non cancellable clear waiting drain survives caller cancellation`() = runBlocking {
        database { t ->
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val active = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.operation {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            val generation = coordinator.generation
            val restore = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { } }
            val work = launch(start = CoroutineStart.UNDISPATCHED) { t.clear.await(listOf(1), false, generation) }
            work.cancel()
            assertFalse(work.isCompleted)
            restore.cancelAndJoin()
            work.join()
            assertEquals(generation, coordinator.generation)
            assertFalse(t.exists(0))
            release.complete(Unit)
            active.join()
        }
    }

    @Test
    fun `nested history and clear operations retain external lease with exclusive pending`() = runBlocking {
        database { t ->
            val target = t.target()
            coordinator.operation {
                var entered = false
                val restore = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
                assertTrue(t.remove.await(target))
                assertTrue(t.remove.await(t.manga, target.generation))
                assertTrue(t.clear.await(listOf(2), true, target.generation))
                assertTrue(t.remove.awaitAll(target.generation))
                assertFalse(entered)
                restore.cancelAndJoin()
            }
        }
    }
}
