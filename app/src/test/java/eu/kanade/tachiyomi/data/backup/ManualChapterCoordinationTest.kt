package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.chapter.interactor.SetBookmarkStatus
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.download.interactor.DeleteDownload
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.app.di.AppBindings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga

/** SQLite application and real manual interactors; download deletion is synthetic. */
class ManualChapterCoordinationTest {
    private val coordinator = LibraryOperationCoordinator.shared
    private enum class Kind {
        SNAPSHOT,
        MANGA_ID,
        MANGA,
        NOTIFICATION_URLS,
        BOOKMARK,
    }

    private class Target(val database: Database) {
        val mangas = MangaRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database)
        val preferences = DownloadPreferences(InMemoryPreferenceStore())
        val delete = mockk<DeleteDownload>()
        lateinit var manga: Manga
        lateinit var chapter: Chapter
        var reads = 0
        var writes = 0
        var deletes = 0
        var deleted = emptyList<Chapter>()
        var result: SetReadStatus.Result? = null
        var onRead: suspend () -> Unit = {}
        var onWrite: suspend () -> Unit = {}
        var onDelete: suspend () -> Unit = {}
        val repository = object : ChapterRepository by chapters {
            override suspend fun getChapterById(id: Long): Chapter? {
                reads++
                onRead()
                return chapters.getChapterById(id)
            }
            override suspend fun getChapterByMangaId(
                mangaId: Long,
                applyScanlatorFilter: Boolean,
            ): List<Chapter> {
                reads++
                onRead()
                return chapters.getChapterByMangaId(mangaId, applyScanlatorFilter)
            }
            override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? {
                reads++
                onRead()
                return chapters.getChapterByUrlAndMangaId(url, mangaId)
            }
            override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
                onWrite()
                chapters.updateAll(chapterUpdates)
                writes++
            }
        }
        val read = SetReadStatus(preferences, delete, mangas, repository)
        val bookmark = SetBookmarkStatus(repository)

        init {
            preferences.removeAfterMarkedAsRead.set(true)
            coEvery { delete.awaitAll(any(), *anyVararg()) } coAnswers {
                deleted = secondArg<Array<Chapter>>().toList()
                onDelete()
                deletes++
                Unit
            }
        }

        suspend fun seed() {
            manga = mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 1, url = "/synthetic", title = "synthetic")),
            ).single()
            chapter = chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/chapter",
                        name = "initial",
                        lastPageRead = 5,
                    ),
                ),
            ).single()
        }

        suspend fun execute(kind: Kind) {
            when (kind) {
                Kind.SNAPSHOT -> {
                    result = read.await(true, chapter)
                }
                Kind.MANGA_ID -> {
                    result = read.await(manga.id, true)
                }
                Kind.MANGA -> {
                    result = read.await(manga, true)
                }
                Kind.NOTIFICATION_URLS -> {
                    result = read.await(
                        true,
                        manga.id,
                        arrayOf(chapter.url),
                    )
                }
                Kind.BOOKMARK -> bookmark.await(true, chapter)
            }
        }

        suspend fun current() = chapters.getChapterById(chapter.id)!!
        suspend fun assertUntouched() {
            assertEquals(0, reads)
            assertEquals(0, writes)
            assertEquals(0, deletes)
            assertEquals(chapter, current())
        }
    }

    // SQLite maintains these two audit fields through triggers on every real mutation.
    private fun assertUpdatedContent(expected: Chapter, actual: Chapter) {
        assertEquals(
            expected,
            actual.copy(
                lastModifiedAt = expected.lastModifiedAt,
                version = expected.version,
            ),
        )
        assertTrue(actual.version > expected.version)
    }

    private suspend fun database(block: suspend (Target) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.create(driver).await()
            val target = Target(AppBindings.providesDatabase(driver))
            target.seed()
            block(target)
        }
    }

    @Test
    fun `manual intents retain lease through local effects`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val reading = CompletableDeferred<Unit>()
                val finishRead = CompletableDeferred<Unit>()
                val writing = CompletableDeferred<Unit>()
                val finishWrite = CompletableDeferred<Unit>()
                val deleting = CompletableDeferred<Unit>()
                val finishDelete = CompletableDeferred<Unit>()
                t.onRead = {
                    reading.complete(Unit)
                    finishRead.await()
                }
                t.onWrite = {
                    writing.complete(Unit)
                    finishWrite.await()
                }
                t.onDelete = {
                    deleting.complete(Unit)
                    finishDelete.await()
                }
                val call = launch(start = CoroutineStart.UNDISPATCHED) { t.execute(kind) }
                reading.await()
                var entered = false
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.exclusive { entered = true }
                }
                assertFalse(entered)
                finishRead.complete(Unit)
                writing.await()
                assertFalse(entered)
                assertEquals(t.chapter, t.current())
                finishWrite.complete(Unit)
                if (kind != Kind.BOOKMARK) {
                    deleting.await()
                    assertFalse(entered)
                    assertEquals(t.chapter, t.deleted.single())
                    finishDelete.complete(Unit)
                }
                call.join()
                exclusive.join()
                assertTrue(entered)
                assertUpdatedContent(
                    t.chapter.copy(
                        read = kind != Kind.BOOKMARK,
                        bookmark = kind == Kind.BOOKMARK,
                    ),
                    t.current(),
                )
            }
        }
    }

    @Test
    fun `stale manual intents finish without effects`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val release = CompletableDeferred<Unit>()
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.exclusive { release.await() }
                }
                val call = async(start = CoroutineStart.UNDISPATCHED) { t.execute(kind) }
                assertFalse(call.isCompleted)
                t.assertUntouched()
                release.complete(Unit)
                exclusive.join()
                call.await()
                t.assertUntouched()
                if (kind != Kind.BOOKMARK) assertEquals(SetReadStatus.Result.NoChapters, t.result)
            }
        }
    }

    @Test
    fun `cancelled exclusive leaves waiting manual intents valid`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val release = CompletableDeferred<Unit>()
                val participant = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.operation { release.await() }
                }
                val generation = coordinator.generation
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.exclusive { error("unexpected application") }
                }
                val call = async(start = CoroutineStart.UNDISPATCHED) { t.execute(kind) }
                assertFalse(call.isCompleted)
                exclusive.cancelAndJoin()
                assertEquals(generation, coordinator.generation)
                call.await()
                assertEquals(1, t.writes)
                release.complete(Unit)
                participant.join()
            }
        }
    }

    @Test
    fun `read intent uses current chapter and narrow update`() = runBlocking {
        database { t ->
            t.chapters.update(
                ChapterUpdate(
                    id = t.chapter.id,
                    read = true,
                    lastPageRead = 12,
                    bookmark = true,
                    name = "current",
                ),
            )
            assertEquals(SetReadStatus.Result.NoChapters, t.read.await(true, t.chapter))
            assertEquals(0, t.writes)
            assertEquals(0, t.deletes)
            val current = t.current()
            assertEquals(SetReadStatus.Result.Success, t.read.await(false, t.chapter))
            assertUpdatedContent(current.copy(read = false, lastPageRead = 0), t.current())
            t.chapters.update(ChapterUpdate(id = t.chapter.id, lastPageRead = 9))
            val unread = t.current()
            t.read.await(true, t.chapter.copy(read = true))
            assertUpdatedContent(unread.copy(read = true), t.current())
            assertEquals(unread, t.deleted.single())
        }
    }

    @Test
    fun `snapshot identity mismatch or absent chapter never writes or deletes`() = runBlocking {
        for (bookmark in listOf(false, true)) {
            database { t ->
                val invalid = listOf(
                    t.chapter.copy(id = t.chapter.id + 100),
                    t.chapter.copy(mangaId = t.manga.id + 100),
                    t.chapter.copy(url = "/different"),
                )
                for (expected in invalid) {
                    if (bookmark) {
                        t.bookmark.await(true, expected)
                    } else {
                        assertEquals(SetReadStatus.Result.NoChapters, t.read.await(true, expected))
                    }
                }
                assertEquals(0, t.writes)
                assertEquals(0, t.deletes)
                assertEquals(t.chapter, t.current())
            }
        }
    }

    @Test
    fun `bookmark handles batch from current state and changes only bookmark`() = runBlocking {
        database { t ->
            val second = t.chapters
                .addAll(
                    listOf(
                        t.chapter.copy(
                            id = 0,
                            url = "/second",
                        ),
                    ),
                )
                .single()
            t.chapters.update(
                ChapterUpdate(
                    id = t.chapter.id,
                    read = true,
                    lastPageRead = 14,
                    name = "current",
                ),
            )
            val current = t.current()
            t.bookmark.await(true, t.chapter.copy(bookmark = true), second)
            assertUpdatedContent(current.copy(bookmark = true), t.current())
            assertUpdatedContent(
                second.copy(bookmark = true),
                t.chapters.getChapterById(second.id)!!,
            )
            assertEquals(1, t.writes)
            t.bookmark.await(true, t.chapter, second)
            assertEquals(1, t.writes)
            t.bookmark.await(false, t.chapter)
            assertUpdatedContent(current, t.current())
        }
    }

    @Test
    fun `nested manual operations retain outer lease`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val begin = CompletableDeferred<Unit>()
                val complete = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val outer = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.operation {
                        begin.await()
                        t.execute(kind)
                        complete.complete(Unit)
                        release.await()
                    }
                }
                var entered = false
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.exclusive { entered = true }
                }
                begin.complete(Unit)
                complete.await()
                assertFalse(entered)
                assertEquals(1, t.writes)
                release.complete(Unit)
                outer.join()
                exclusive.join()
                assertTrue(entered)
            }
        }
    }

    @Test
    fun `cancelled read caller retains non cancellable operation through delete`() = runBlocking {
        database { t ->
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            t.onDelete = {
                entered.complete(Unit)
                finish.await()
            }
            val call = launch(start = CoroutineStart.UNDISPATCHED) {
                t.read.await(true, t.chapter)
            }
            entered.await()
            call.cancel()
            var applied = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive { applied = true }
            }
            assertFalse(applied)
            finish.complete(Unit)
            call.join()
            exclusive.join()
            assertEquals(1, t.deletes)
            assertTrue(applied)
        }
    }

    @Test
    fun `bookmark cancellation propagates without writes`() = runBlocking {
        database { t ->
            val finish = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive { finish.await() }
            }
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                t.bookmark.await(true, t.chapter)
            }
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
            t.assertUntouched()
            finish.complete(Unit)
            exclusive.join()
        }
        database { t ->
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            t.onRead = {
                entered.complete(Unit)
                finish.await()
            }
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                t.bookmark.await(true, t.chapter)
            }
            entered.await()
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
            assertEquals(0, t.writes)
            coordinator.exclusive { }
        }
    }
}
