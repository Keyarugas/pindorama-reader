package eu.kanade.tachiyomi.data.backup

import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.RemoveLibraryMangas
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mihon.app.di.AppBindings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/** Real SQLite, concrete manual intentions and synthetic cover/download effects. */
class ManualMangaStateCoordinationPart2Test {
    private val coordinator = LibraryOperationCoordinator.shared
    private enum class Kind { NOTES, SCANLATORS, RESET, REMOVE }

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
        val covers = mockk<CoverCache>()
        val downloads = mockk<DownloadManager>()
        val sources = mockk<SourceManager>()
        val source = mockk<HttpSource>()
        lateinit var manga: Manga
        lateinit var second: Manga
        var reads = 0
        var resets = 0
        var coverDeletes = 0
        val downloadDeletes = mutableListOf<Manga>()
        val updates = mutableListOf<MangaUpdate>()
        var onRead: suspend () -> Unit = {}
        var onWrite: suspend () -> Unit = {}
        var onDelete: suspend () -> Unit = {}
        val repository = object : MangaRepository by mangas {
            override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
                reads++
                onRead()
                return mangas.getMangaByUrlAndSourceId(url, sourceId)
            }
            override suspend fun update(update: MangaUpdate): Boolean {
                onWrite()
                updates.add(update)
                return mangas.update(update)
            }
            override suspend fun updateAll(mangaUpdates: List<MangaUpdate>): Boolean {
                onWrite()
                updates.addAll(mangaUpdates)
                return mangas.updateAll(mangaUpdates)
            }
            override suspend fun resetViewerFlags(): Boolean {
                resets++
                onWrite()
                return mangas.resetViewerFlags()
            }
        }
        val notes = UpdateMangaNotes(repository)
        val scanlators = SetExcludedScanlators(database, repository)
        val reset = ResetViewerFlags(repository)
        val remove = RemoveLibraryMangas(repository, covers, sources, downloads)

        init {
            every { covers.deleteFromCache(any(), true) } answers {
                coverDeletes++
                0
            }
            coEvery { sources.get(any()) } returns source
            coEvery { downloads.deleteMangaAndWait(any(), any(), any()) } coAnswers {
                onDelete()
                downloadDeletes.add(firstArg())
                Unit
            }
        }

        suspend fun seed() {
            val entries = mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(source = 1, url = "/first", title = "first", favorite = true),
                    Manga.create().copy(source = 1, url = "/second", title = "second", favorite = true),
                ),
            )
            manga = entries[0]
            second = entries[1]
        }

        suspend fun execute(kind: Kind, generation: Long = LibraryOperationCoordinator.shared.generation) {
            when (kind) {
                Kind.NOTES -> notes(manga, "edited", generation)
                Kind.SCANLATORS -> scanlators.await(manga, setOf("selected"), generation)
                Kind.RESET -> reset.await(generation)
                Kind.REMOVE -> remove.await(listOf(manga, second), true, true, generation)
            }
        }

        suspend fun current() = mangas.getMangaById(manga.id)
        suspend fun exclusions() = database.excluded_scanlatorsQueries
            .getExcludedScanlatorsByMangaId(manga.id).awaitAsList().toSet()

        fun assertNoEffects() {
            assertEquals(0, reads)
            assertEquals(0, resets)
            assertTrue(updates.isEmpty())
            assertEquals(0, coverDeletes)
            assertTrue(downloadDeletes.isEmpty())
        }
    }

    private suspend fun database(block: suspend (Target) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { sqlite ->
            val driver = Driver(sqlite)
            Database.Schema.create(driver).await()
            val target = Target(AppBindings.providesDatabase(driver), driver)
            target.seed()
            block(target)
        }
    }

    private fun assertContent(expected: Manga, actual: Manga) {
        assertEquals(
            expected,
            actual.copy(
                version = expected.version,
                lastModifiedAt = expected.lastModifiedAt,
                favoriteModifiedAt = expected.favoriteModifiedAt,
            ),
        )
    }

    @Test
    fun `old editor cannot overwrite restored notes or report success`() = runBlocking {
        database { t ->
            val editorGeneration = coordinator.generation
            val editor = t.notes.loadEditor(t.manga, editorGeneration)!!
            t.reads = 0
            coordinator.exclusive { t.mangas.update(MangaUpdate(t.manga.id, notes = "restored")) }
            assertFalse(t.notes(editor, "old edited content", editorGeneration))
            t.assertNoEffects()
            assertEquals("restored", t.current().notes)
        }
    }

    @Test
    fun `opening editors loads current content and refuses obsolete generation before reads`() = runBlocking {
        database { t ->
            val oldGeneration = coordinator.generation
            coordinator.exclusive {
                t.mangas.update(MangaUpdate(t.manga.id, notes = "restored"))
                t.database.excluded_scanlatorsQueries.insert(t.manga.id, "restored")
            }
            assertNull(t.notes.loadEditor(t.manga, oldGeneration))
            assertNull(t.scanlators.loadSelection(t.manga, oldGeneration))
            t.assertNoEffects()
            assertEquals("restored", t.notes.loadEditor(t.manga, coordinator.generation)?.notes)
            assertEquals(setOf("restored"), t.scanlators.loadSelection(t.manga, coordinator.generation))
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `notes model retains editor generation and updates saved state only on success`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            for (stale in listOf(false, true)) {
                database { t ->
                    val model = MangaNotesScreen.Model(t.manga, coordinator.generation, t.notes)
                    try {
                        if (stale) {
                            coordinator.exclusive { t.mangas.update(MangaUpdate(t.manga.id, notes = "restored")) }
                        }
                        model.updateNotes("edited")
                        model.viewModelScope.coroutineContext.job.children.toList().forEach { it.join() }
                        assertEquals(if (stale) t.manga.notes else "edited", model.state.value.notes)
                        assertEquals(if (stale) "restored" else "edited", t.current().notes)
                        if (stale) t.assertNoEffects()
                    } finally {
                        model.viewModelScope.cancel()
                    }
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `current editor changes only notes`() = runBlocking {
        database { t ->
            t.mangas.update(
                MangaUpdate(t.manga.id, notes = "current", isPrivate = true, chapterFlags = 73, viewerFlags = 5),
            )
            val before = t.current()
            assertTrue(t.notes(t.manga, "edited", coordinator.generation))
            assertContent(before.copy(notes = "edited"), t.current())
            assertEquals(listOf(MangaUpdate(t.manga.id, notes = "edited")), t.updates)
        }
    }

    @Test
    fun `notes cancellation at admission or requery has no writes`() = runBlocking {
        database { t ->
            val release = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { release.await() } }
            val call = async(start = CoroutineStart.UNDISPATCHED) { t.execute(Kind.NOTES) }
            call.cancelAndJoin()
            t.assertNoEffects()
            release.complete(Unit)
            exclusive.join()
        }
        database { t ->
            val reading = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            t.onRead = {
                reading.complete(Unit)
                release.await()
            }
            val call = async(start = CoroutineStart.UNDISPATCHED) { t.execute(Kind.NOTES) }
            reading.await()
            call.cancelAndJoin()
            assertTrue(t.updates.isEmpty())
            coordinator.exclusive { }
        }
    }

    @Test
    fun `stale scanlator selection leaves restored exclusions untouched`() = runBlocking {
        database { t ->
            val selectionGeneration = coordinator.generation
            t.database.excluded_scanlatorsQueries.insert(t.manga.id, "old selection")
            val selection = t.scanlators.loadSelection(t.manga, selectionGeneration)!!
            t.reads = 0
            coordinator.exclusive {
                t.database.excluded_scanlatorsQueries.remove(t.manga.id, selection)
                t.database.excluded_scanlatorsQueries.insert(t.manga.id, "restored")
            }
            assertFalse(t.scanlators.await(t.manga, selection, selectionGeneration))
            t.assertNoEffects()
            assertEquals(setOf("restored"), t.exclusions())
        }
    }

    @Test
    fun `current scanlator selection applies insert and remove transaction`() = runBlocking {
        database { t ->
            t.database.excluded_scanlatorsQueries.insert(t.manga.id, "remove")
            t.database.excluded_scanlatorsQueries.insert(t.manga.id, "keep")
            assertTrue(t.scanlators.await(t.manga, setOf("keep", "add"), coordinator.generation))
            assertEquals(setOf("keep", "add"), t.exclusions())
        }
    }

    @Test
    fun `scanlator transaction rolls back on synthetic write failure`() = runBlocking {
        database { t ->
            t.database.excluded_scanlatorsQueries.insert(t.manga.id, "original")
            var inserts = 0
            t.driver.onSql = { sql ->
                if (sql.startsWith("INSERT INTO excluded_scanlators") && ++inserts == 2) error("SYNTHETIC_FAILURE")
            }
            val failure = runCatching {
                t.scanlators.await(t.manga, linkedSetOf("first", "second"), coordinator.generation)
            }.exceptionOrNull()
            assertEquals("SYNTHETIC_FAILURE", failure?.message)
            t.driver.onSql = {}
            assertEquals(setOf("original"), t.exclusions())
            coordinator.exclusive { }
        }
    }

    @Test
    fun `scanlator lease covers reading insert and removal inside transaction`() = runBlocking {
        for (phase in listOf(
            "SELECT scanlator",
            "INSERT INTO excluded_scanlators",
            "DELETE FROM excluded_scanlators",
        )) {
            database { t ->
                t.database.excluded_scanlatorsQueries.insert(t.manga.id, "original")
                val reached = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                t.driver.onSql = { sql ->
                    if (sql.startsWith(phase)) {
                        reached.complete(Unit)
                        release.await()
                    }
                }
                val call = launch(start = CoroutineStart.UNDISPATCHED) { t.execute(Kind.SCANLATORS) }
                reached.await()
                var entered = false
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
                assertFalse(entered)
                release.complete(Unit)
                call.join()
                exclusive.join()
                t.driver.onSql = {}
                assertTrue(entered)
                assertEquals(setOf("selected"), t.exclusions())
            }
        }
    }

    @Test
    fun `stale viewer reset preserves restored flags and has no feedback result`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            coordinator.exclusive { t.mangas.update(MangaUpdate(t.manga.id, viewerFlags = 5)) }
            assertNull(t.reset.await(generation))
            t.assertNoEffects()
            assertEquals(5L, t.current().viewerFlags)
        }
    }

    @Test
    fun `viewer reset covers all targets under one lease`() = runBlocking {
        database { t ->
            t.mangas.update(MangaUpdate(t.manga.id, viewerFlags = 5))
            t.mangas.update(MangaUpdate(t.second.id, viewerFlags = 7))
            val writing = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            t.onWrite = {
                writing.complete(Unit)
                release.await()
            }
            val call = async(start = CoroutineStart.UNDISPATCHED) { t.reset.await() }
            writing.await()
            var entered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
            assertFalse(entered)
            release.complete(Unit)
            assertEquals(true, call.await())
            exclusive.join()
            assertTrue(entered)
            assertEquals(1, t.resets)
            assertEquals(0L, t.current().viewerFlags)
            assertEquals(0L, t.mangas.getMangaById(t.second.id).viewerFlags)
        }
    }

    @Test
    fun `stale removal does not touch membership covers or downloads`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            coordinator.exclusive { t.mangas.update(MangaUpdate(t.manga.id, notes = "restored")) }
            t.execute(Kind.REMOVE, generation)
            t.assertNoEffects()
            assertTrue(t.current().favorite)
        }
    }

    @Test
    fun `removal revalidates current identity and preserves unrelated fields and date added`() = runBlocking {
        database { t ->
            t.manga = t.manga.copy(favorite = false)
            t.mangas.update(
                MangaUpdate(
                    id = t.manga.id,
                    dateAdded = 99,
                    isPrivate = true,
                    notes = "current",
                    chapterFlags = 73,
                    viewerFlags = 5,
                    title = "current title",
                ),
            )
            val before = t.current()
            t.remove.await(listOf(t.manga), true, true)
            assertContent(before.copy(favorite = false), t.current())
            assertEquals(listOf(MangaUpdate(t.manga.id, favorite = false)), t.updates)
            assertEquals(listOf(before), t.downloadDeletes)
            assertEquals(1, t.coverDeletes)
        }
    }

    @Test
    fun `removal batch owns requery write and final download effect`() = runBlocking {
        for (phase in listOf("requery", "write", "download")) {
            database { t ->
                val reached = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val pause: suspend () -> Unit = {
                    reached.complete(Unit)
                    release.await()
                }
                when (phase) {
                    "requery" -> t.onRead = pause
                    "write" -> t.onWrite = pause
                    else -> t.onDelete = pause
                }
                val call = launch(start = CoroutineStart.UNDISPATCHED) { t.execute(Kind.REMOVE) }
                reached.await()
                var entered = false
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
                assertFalse(entered)
                release.complete(Unit)
                call.join()
                exclusive.join()
                assertTrue(entered)
                assertEquals(2, t.updates.size)
                assertEquals(2, t.coverDeletes)
                assertEquals(2, t.downloadDeletes.size)
            }
        }
    }

    @Test
    fun `identity mismatch prevents notes exclusions and removal effects`() = runBlocking {
        for (kind in listOf(Kind.NOTES, Kind.SCANLATORS, Kind.REMOVE)) {
            for (identity in listOf("id", "source", "url")) {
                database { t ->
                    t.manga = when (identity) {
                        "id" -> t.manga.copy(id = t.manga.id + 100)
                        "source" -> t.manga.copy(source = 2)
                        else -> t.manga.copy(url = "/absent")
                    }
                    if (kind == Kind.REMOVE) {
                        t.remove.await(listOf(t.manga), true, true)
                    } else {
                        t.execute(kind)
                    }
                    assertTrue(t.updates.isEmpty())
                    assertEquals(0, t.coverDeletes)
                    assertTrue(t.downloadDeletes.isEmpty())
                    assertTrue(t.exclusions().isEmpty())
                }
            }
        }
    }

    @Test
    fun `intentions waiting behind application discard before reading or effects`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val release = CompletableDeferred<Unit>()
                val exclusive =
                    launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { release.await() } }
                val call = async(start = CoroutineStart.UNDISPATCHED) { t.execute(kind) }
                assertFalse(call.isCompleted)
                t.assertNoEffects()
                release.complete(Unit)
                exclusive.join()
                call.await()
                t.assertNoEffects()
            }
        }
    }

    @Test
    fun `nested intentions do not release outer lease with exclusive pending`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val begin = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val outer = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.operation {
                        begin.await()
                        t.execute(kind)
                        finished.complete(Unit)
                        release.await()
                    }
                }
                var entered = false
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
                begin.complete(Unit)
                finished.await()
                assertFalse(entered)
                release.complete(Unit)
                outer.join()
                exclusive.join()
                assertTrue(entered)
            }
        }
    }

    @Test
    fun `exclusive cancelled before application does not invalidate intentions`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val release = CompletableDeferred<Unit>()
                val participant =
                    launch(start = CoroutineStart.UNDISPATCHED) { coordinator.operation { release.await() } }
                val generation = coordinator.generation
                val exclusive =
                    launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { error("UNEXPECTED") } }
                val call = async(start = CoroutineStart.UNDISPATCHED) { t.execute(kind) }
                exclusive.cancelAndJoin()
                call.await()
                assertEquals(generation, coordinator.generation)
                when (kind) {
                    Kind.NOTES, Kind.REMOVE -> assertTrue(t.updates.isNotEmpty())
                    Kind.SCANLATORS -> assertEquals(setOf("selected"), t.exclusions())
                    Kind.RESET -> assertEquals(1, t.resets)
                }
                release.complete(Unit)
                participant.join()
            }
        }
    }
}
