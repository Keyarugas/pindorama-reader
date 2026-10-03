package mihon.domain.source.interactor

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import io.mockk.coEvery
import io.mockk.every
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/** Real interactor and SQLite repositories; source and chapter synchronization are controlled collaborators. */
class UpdateMangaFromRemoteCoordinationTest {
    private val coordinator = LibraryOperationCoordinator.shared

    private class Target(val database: Database, val driver: JdbcSqliteDriver) {
        val mangas = MangaRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database)
        val source = mockk<Source>()
        val sources = mockk<SourceManager>()
        val sync = mockk<SyncChaptersWithSource>()
        val events = mutableListOf<String>()
        lateinit var snapshot: Manga
        var requestedManga: SManga? = null
        var requestedChapters: List<SChapter> = emptyList()
        var synchronizedManga: Manga? = null
        var network: suspend () -> Unit = {}
        var synchronize: suspend (Manga) -> List<Chapter> = { emptyList() }
        var finalRead: suspend () -> Unit = {}
        val repository = object : MangaRepository by mangas {
            override suspend fun getMangaById(id: Long): Manga {
                events += "final-read"
                finalRead()
                return mangas.getMangaById(id)
            }

            override suspend fun update(update: MangaUpdate): Boolean {
                events += "metadata-write"
                return mangas.update(update)
            }
        }
        val interactor = UpdateMangaFromRemote(
            sources,
            chapters,
            repository,
            sync,
            mockk(),
            LibraryPreferences(InMemoryPreferenceStore()),
            mockk(relaxed = true),
        )

        init {
            every { source.id } returns 1L
            coEvery { sources.getOrStub(1) } returns source
            coEvery { source.getMangaUpdate(any(), any(), any(), any()) } coAnswers {
                requestedManga = firstArg()
                requestedChapters = secondArg()
                network()
                // Written on the test coroutine after the IO request has returned.
                SMangaUpdate(
                    SManga.create().apply {
                        url = "/synthetic"
                        title = "remote synthetic"
                        author = "remote author"
                    },
                    emptyList(),
                )
            }
            coEvery { sync.await(any(), any(), any(), any(), any()) } coAnswers {
                val manga = secondArg<Manga>()
                synchronizedManga = manga
                events += "sync"
                synchronize(manga)
            }
        }

        suspend fun seed() {
            snapshot = mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 1, url = "/synthetic", title = "old synthetic")),
            ).single()
        }
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
    fun `admitted remote update retains ownership through network chapter write and final read`() = runBlocking {
        database { t ->
            val requested = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            val writing = CompletableDeferred<Unit>()
            val finishWrite = CompletableDeferred<Unit>()
            val reading = CompletableDeferred<Unit>()
            val finishRead = CompletableDeferred<Unit>()
            t.network = {
                requested.complete(Unit)
                response.await()
            }
            t.synchronize = { manga ->
                writing.complete(Unit)
                finishWrite.await()
                t.chapters.addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/new", name = "synthetic")))
            }
            t.finalRead = {
                reading.complete(Unit)
                finishRead.await()
            }
            val operation =
                async(start = CoroutineStart.UNDISPATCHED) { t.interactor(t.source, t.snapshot, fetchChapters = true) }
            requested.await()
            var exclusiveEntered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive {
                    exclusiveEntered = true
                    t.events += "exclusive"
                }
            }
            assertFalse(exclusiveEntered)
            response.complete(Unit)
            writing.await()
            assertFalse(exclusiveEntered)
            finishWrite.complete(Unit)
            reading.await()
            assertFalse(exclusiveEntered)
            finishRead.complete(Unit)
            assertEquals("remote author", operation.await().getOrThrow().manga.author)
            exclusive.join()
            assertTrue(exclusiveEntered)
            assertEquals(listOf("metadata-write", "sync", "final-read", "exclusive"), t.events)
            assertEquals("/new", t.chapters.getChapterByMangaId(t.snapshot.id).single().url)
        }
    }

    @Test
    fun `snapshot awaiting admission uses restored state for request and synchronization`() = runBlocking {
        database { t ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive {
                    entered.complete(Unit)
                    release.await()
                    assertTrue(
                        t.mangas.update(
                            MangaUpdate(
                                t.snapshot.id,
                                title = "restored synthetic",
                                favorite = true,
                                viewerFlags = 123,
                                isPrivate = true,
                            ),
                        ),
                    )
                    t.chapters.addAll(
                        listOf(
                            Chapter.create().copy(
                                mangaId = t.snapshot.id,
                                url = "/restored",
                                name = "restored chapter",
                                read = true,
                            ),
                        ),
                    )
                }
            }
            entered.await()
            val operation =
                async(start = CoroutineStart.UNDISPATCHED) {
                    t.interactor(t.snapshot, fetchDetails = true, fetchChapters = true)
                }
            assertNull(t.requestedManga)
            assertTrue(t.events.isEmpty())
            release.complete(Unit)
            exclusive.join()
            val result = operation.await().getOrThrow()
            assertEquals("restored synthetic", t.requestedManga?.title)
            assertEquals("/restored", t.requestedChapters.single().url)
            assertEquals("restored chapter", t.requestedChapters.single().name)
            assertEquals("restored synthetic", result.manga.title)
            assertTrue(checkNotNull(t.synchronizedManga).favorite)
            assertEquals(123L, t.synchronizedManga?.viewerFlags)
            assertTrue(checkNotNull(t.synchronizedManga).isPrivate)
            assertTrue(result.manga.isPrivate)
            assertEquals(123L, result.manga.viewerFlags)
        }
    }

    @Test
    fun `changed source url id or removed identity during admission refuses network and writes`() = runBlocking {
        for (change in 0..3) {
            database { t ->
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.exclusive {
                        entered.complete(Unit)
                        release.await()
                        when (change) {
                            0 -> assertTrue(t.mangas.update(MangaUpdate(t.snapshot.id, source = 2)))
                            1 -> assertTrue(t.mangas.update(MangaUpdate(t.snapshot.id, url = "/changed")))
                            2 -> t.driver.execute(null, "UPDATE mangas SET _id = _id + 100", 0).await()
                            3 -> t.driver.execute(null, "DELETE FROM mangas", 0).await()
                        }
                    }
                }
                entered.await()
                val operation =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        t.interactor(t.source, t.snapshot, fetchChapters = true)
                    }
                assertNull(t.requestedManga)
                release.complete(Unit)
                exclusive.join()
                val error = checkNotNull(operation.await().exceptionOrNull())
                assertEquals("MANGA_STATE_CHANGED", error.message)
                assertNull(error.cause)
                assertNull(t.requestedManga)
                assertNull(t.synchronizedManga)
                assertTrue(t.events.isEmpty())
            }
        }
    }

    @Test
    fun `nested update completes during pending exclusive and outer participation stays active`() = runBlocking {
        database { t ->
            val entered = CompletableDeferred<Unit>()
            val startUpdate = CompletableDeferred<Unit>()
            val updated = CompletableDeferred<Unit>()
            val releaseOuter = CompletableDeferred<Unit>()
            val outer = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.operation {
                    entered.complete(Unit)
                    startUpdate.await()
                    t.interactor(t.snapshot, fetchDetails = true).getOrThrow()
                    updated.complete(Unit)
                    releaseOuter.await()
                }
            }
            entered.await()
            var exclusiveEntered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive {
                    exclusiveEntered =
                        true
                }
            }
            startUpdate.complete(Unit)
            updated.await()
            assertFalse(exclusiveEntered)
            releaseOuter.complete(Unit)
            outer.join()
            exclusive.join()
            assertTrue(exclusiveEntered)
        }
    }

    @Test
    fun `cancellation waiting for admission never starts request or local effects`() = runBlocking {
        database { t ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val operation =
                async(start = CoroutineStart.UNDISPATCHED) { t.interactor(t.snapshot, fetchChapters = true) }
            operation.cancelAndJoin()
            release.complete(Unit)
            exclusive.join()
            assertNull(t.requestedManga)
            assertTrue(t.events.isEmpty())
            assertEquals(t.snapshot, t.mangas.getMangaById(t.snapshot.id))
            assertTrue(t.chapters.getChapterByMangaId(t.snapshot.id).isEmpty())
        }
    }
}
