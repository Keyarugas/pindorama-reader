package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.RegisterTracking
import eu.kanade.domain.track.interactor.SyncChapterProgressWithTrack
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.Source
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/** Real interactors, sync and SQLite; only the remote tracker is synthetic. */
class TrackingIntentCoordinationTest {
    private val coordinator = LibraryOperationCoordinator.shared
    private abstract class SyntheticTracker : BaseTracker(1, "synthetic"), EnhancedTracker
    private enum class Kind {
        BIND,
        ENHANCED,
        REFRESH,
    }

    private class Target(val database: Database) {
        val mangas = MangaRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database)
        val tracks = TrackRepositoryImpl(database)
        val service = mockk<SyntheticTracker>()
        val manager = mockk<TrackerManager>()
        val source = mockk<Source>()
        lateinit var manga: Manga
        lateinit var track: Track
        var requests = 0
        var matches = 0
        var inserts = 0
        var chapterWrites = 0
        var chapterReads = 0
        var trackReads = 0
        var mangaReads = 0
        var matched: Manga? = null
        var remote: suspend () -> Unit = {}
        var beforeMatch: suspend () -> Unit = {}
        var beforeSearch: suspend () -> Unit = {}
        var remoteSync: suspend () -> Unit = {}
        var beforeInsert: suspend (Int) -> Unit = {}
        var beforeChapters: suspend () -> Unit = {}
        private val trackingRepository = object : TrackRepository by tracks {
            override suspend fun getTracksByMangaId(mangaId: Long): List<Track> {
                trackReads++
                return tracks.getTracksByMangaId(mangaId)
            }
            override suspend fun insert(track: Track) {
                inserts++
                beforeInsert(inserts)
                tracks.insert(track)
            }
        }
        private val chapterRepository = object : ChapterRepository by chapters {
            override suspend fun getChapterByMangaId(
                mangaId: Long,
                applyScanlatorFilter: Boolean,
            ): List<Chapter> {
                chapterReads++
                return chapters.getChapterByMangaId(mangaId, applyScanlatorFilter)
            }
            override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
                beforeChapters()
                chapters.updateAll(chapterUpdates)
                chapterWrites++
            }
        }
        private val mangaRepository = object : MangaRepository by mangas {
            override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
                mangaReads++
                return mangas.getMangaByUrlAndSourceId(url, sourceId)
            }
        }
        private val insert = InsertTrack(trackingRepository)
        private val getChapters = GetChaptersByMangaId(chapterRepository)
        private val sync = SyncChapterProgressWithTrack(
            UpdateChapter(chapterRepository),
            insert,
            getChapters,
        )
        val add = AddTracks(
            insert,
            sync,
            getChapters,
            manager,
            GetHistory(HistoryRepositoryImpl(database)),
            mangaRepository,
        )
        val register = RegisterTracking(GetManga(mangas), trackingRepository, manager, add)
        val refresh = RefreshTracks(GetTracks(trackingRepository), manager, insert, sync)

        init {
            every { manager.get(1) } returns service
            every { manager.loggedInTrackers() } returns listOf(service)
            every { service.isLoggedIn } returns true
            every { service.accept(source) } returns true
            coEvery { service.bind(any(), any()) } coAnswers {
                requests++
                remote()
                firstArg()
            }
            coEvery { service.refresh(any()) } coAnswers {
                requests++
                remote()
                track.copy(lastChapterRead = 1.0).toDbTrack()
            }
            coEvery { service.match(any()) } coAnswers {
                matches++
                beforeMatch()
                matched = firstArg()
                item()
            }
            coEvery { service.search(any()) } coAnswers {
                beforeSearch()
                listOf(item())
            }
            coEvery { service.update(any(), any()) } coAnswers {
                remoteSync()
                firstArg()
            }
        }

        suspend fun seed() {
            manga = mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 1, url = "/synthetic", title = "initial")),
            ).single()
            chapters.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "/chapter",
                        chapterNumber = 1.0,
                    ),
                ),
            )
            tracks.insert(
                Track(
                    0,
                    manga.id,
                    1,
                    10,
                    null,
                    "synthetic",
                    0.0,
                    10,
                    1,
                    0.0,
                    "",
                    1,
                    0,
                    false,
                ),
            )
            track = tracks.getTracksByMangaId(manga.id).single()
        }

        fun item() = TrackSearch.create(1).apply {
            manga_id = manga.id
            remote_id = 10
            title = "synthetic"
            tracking_url = ""
            last_chapter_read = 1.0
            started_reading_date = 1
        }

        suspend fun execute(kind: Kind) = when (kind) {
            Kind.BIND -> {
                add.bind(service, item(), manga.id)
                emptyList()
            }
            Kind.ENHANCED -> {
                add.bindEnhancedTrackers(manga, source)
                emptyList()
            }
            Kind.REFRESH -> refresh.await(manga.id)
        }

        suspend fun assertUntouched() {
            assertEquals(0, requests)
            assertEquals(0, matches)
            assertEquals(0, inserts)
            assertEquals(0, chapterWrites)
            assertEquals(0, chapterReads)
            assertEquals(0, trackReads)
            assertEquals(0, mangaReads)
            assertEquals(track, tracks.getTracksByMangaId(manga.id).single())
            assertFalse(chapters.getChapterByMangaId(manga.id).single().read)
        }
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
    fun `tracking intents retain lease through remote and local effects`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val remote = CompletableDeferred<Unit>()
                val finishRemote = CompletableDeferred<Unit>()
                val insert = CompletableDeferred<Unit>()
                val finishInsert = CompletableDeferred<Unit>()
                val syncRemote = CompletableDeferred<Unit>()
                val finishSyncRemote = CompletableDeferred<Unit>()
                val chapters = CompletableDeferred<Unit>()
                val finishChapters = CompletableDeferred<Unit>()
                val finalInsert = CompletableDeferred<Unit>()
                val finishFinalInsert = CompletableDeferred<Unit>()
                t.remote = {
                    remote.complete(Unit)
                    finishRemote.await()
                }
                t.beforeInsert = { count ->
                    if (count == 1) {
                        insert.complete(Unit)
                        finishInsert.await()
                    } else {
                        finalInsert.complete(Unit)
                        finishFinalInsert.await()
                    }
                }
                t.remoteSync = {
                    syncRemote.complete(Unit)
                    finishSyncRemote.await()
                }
                t.beforeChapters = {
                    chapters.complete(Unit)
                    finishChapters.await()
                }
                val call = async(start = CoroutineStart.UNDISPATCHED) { t.execute(kind) }
                remote.await()
                var entered = false
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.exclusive { entered = true }
                }
                assertFalse(entered)
                finishRemote.complete(Unit)
                insert.await()
                assertFalse(entered)
                finishInsert.complete(Unit)
                syncRemote.await()
                assertFalse(entered)
                finishSyncRemote.complete(Unit)
                chapters.await()
                assertFalse(entered)
                assertFalse(t.chapters.getChapterByMangaId(t.manga.id).single().read)
                finishChapters.complete(Unit)
                finalInsert.await()
                assertTrue(t.chapters.getChapterByMangaId(t.manga.id).single().read)
                assertFalse(entered)
                finishFinalInsert.complete(Unit)
                assertTrue(call.await().isEmpty())
                exclusive.join()
                assertTrue(entered)
                assertEquals(2, t.inserts)
                assertEquals(1.0, t.tracks.getTracksByMangaId(t.manga.id).single().lastChapterRead)
            }
        }
    }

    @Test
    fun `old tracking intents are discarded after generation change`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val release = CompletableDeferred<Unit>()
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.exclusive { release.await() }
                }
                val generation = coordinator.generation
                val call = async(start = CoroutineStart.UNDISPATCHED) { t.execute(kind) }
                assertFalse(call.isCompleted)
                t.assertUntouched()
                release.complete(Unit)
                exclusive.join()
                assertEquals(generation + 1, coordinator.generation)
                assertTrue(call.await().isEmpty())
                t.assertUntouched()
            }
        }
    }

    @Test
    fun `cancelled exclusive does not invalidate waiting tracking`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val release = CompletableDeferred<Unit>()
                val participant = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.operation { release.await() }
                }
                val generation = coordinator.generation
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.exclusive { error("unexpected admission") }
                }
                val call = async(start = CoroutineStart.UNDISPATCHED) { t.execute(kind) }
                assertFalse(call.isCompleted)
                exclusive.cancelAndJoin()
                assertEquals(generation, coordinator.generation)
                assertTrue(call.await().isEmpty())
                assertEquals(1, t.requests)
                assertTrue(t.chapters.getChapterByMangaId(t.manga.id).single().read)
                release.complete(Unit)
                participant.join()
            }
        }
    }

    @Test
    fun `nested tracking calls retain external lease`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val begin = CompletableDeferred<Unit>()
                val completed = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val outer = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.operation {
                        begin.await()
                        t.execute(kind)
                        completed.complete(Unit)
                        release.await()
                    }
                }
                var entered = false
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.exclusive { entered = true }
                }
                begin.complete(Unit)
                completed.await()
                assertFalse(entered)
                assertEquals(1, t.requests)
                release.complete(Unit)
                outer.join()
                exclusive.join()
                assertTrue(entered)
            }
        }
    }

    @Test
    fun `enhanced bind uses current manga and rejects changed identity`() = runBlocking {
        database { t ->
            t.mangas.update(MangaUpdate(t.manga.id, title = "current"))
            t.add.bindEnhancedTrackers(t.manga, t.source)
            assertEquals("current", t.matched?.title)
        }
        for (identity in listOf("id", "source", "url", "missing")) {
            database { t ->
                val expected = when (identity) {
                    "id" -> t.manga.copy(id = t.manga.id + 1)
                    "source" -> t.manga.copy(source = 2)
                    "url" -> t.manga.copy(url = "/different")
                    else -> t.manga.copy(id = -1, url = "/missing")
                }
                t.add.bindEnhancedTrackers(expected, t.source)
                assertEquals(0, t.requests)
                assertEquals(0, t.matches)
                assertEquals(0, t.inserts)
                assertFalse(t.chapters.getChapterByMangaId(t.manga.id).single().read)
            }
        }
    }

    @Test
    fun `cancelled add caller retains admission until completion`() = runBlocking {
        database { t ->
            val remote = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            t.remote = {
                remote.complete(Unit)
                finish.await()
            }
            val call = launch(start = CoroutineStart.UNDISPATCHED) { t.execute(Kind.BIND) }
            remote.await()
            call.cancel()
            var entered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive { entered = true }
            }
            assertFalse(entered)
            finish.complete(Unit)
            call.join()
            exclusive.join()
            assertTrue(entered)
            assertEquals(2, t.inserts)
            assertTrue(t.chapters.getChapterByMangaId(t.manga.id).single().read)
        }
    }

    @Test
    fun `refresh cancellation propagates and releases lease`() = runBlocking {
        database { t ->
            val release = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive { release.await() }
            }
            val call = async(start = CoroutineStart.UNDISPATCHED) { t.refresh.await(t.manga.id) }
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
            t.assertUntouched()
            release.complete(Unit)
            exclusive.join()
        }
        database { t ->
            val remote = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            t.remote = {
                remote.complete(Unit)
                finish.await()
            }
            val call = async(start = CoroutineStart.UNDISPATCHED) { t.refresh.await(t.manga.id) }
            remote.await()
            call.cancelAndJoin()
            assertTrue(call.isCancelled)
            assertEquals(0, t.inserts)
            coordinator.exclusive { }
        }
    }

    @Test
    fun `match response from before restore cannot bind or sync`() = runBlocking {
        database { t ->
            val session = t.register.loadSession(t.manga.id, t.manga.source, 1, coordinator.generation)!!
            val requested = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            t.beforeMatch = {
                requested.complete(Unit)
                response.await()
            }
            val call = async(start = CoroutineStart.UNDISPATCHED) { t.register.matchAndBind(session) }
            requested.await()
            // Remote matching owns no lease: restore can publish while it waits.
            coordinator.exclusive { }
            response.complete(Unit)
            assertEquals(null, call.await())
            assertEquals(0, t.requests)
            assertEquals(0, t.inserts)
            assertEquals(0, t.chapterWrites)
            assertEquals(t.track, t.tracks.getTracksByMangaId(t.manga.id).single())
        }
    }

    @Test
    fun `search response and retained selection keep their pre-request generation`() = runBlocking {
        database { t ->
            val session = t.register.loadSession(t.manga.id, t.manga.source, 1, coordinator.generation)!!
            val requested = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            t.beforeSearch = {
                requested.complete(Unit)
                response.await()
            }
            val call = async(start = CoroutineStart.UNDISPATCHED) { t.register.search(session, "synthetic") }
            requested.await()
            coordinator.exclusive { }
            response.complete(Unit)
            assertEquals(null, call.await())
            assertFalse(t.register.bind(session, t.item()))
            assertEquals(0, t.requests)
            assertEquals(0, t.inserts)
            assertEquals(0, t.chapterWrites)
        }
        database { t ->
            val session = t.register.loadSession(t.manga.id, t.manga.source, 1, coordinator.generation)!!
            val selected = t.register.search(session, "synthetic")!!.single()
            coordinator.exclusive { }
            assertFalse(t.register.bind(session, selected))
            assertEquals(0, t.requests)
            assertEquals(0, t.inserts)
            assertEquals(0, t.chapterWrites)
        }
    }

    @Test
    fun `fresh registration binds and syncs under one nested lease`() = runBlocking {
        database { t ->
            val session = t.register.loadSession(t.manga.id, t.manga.source, 1, coordinator.generation)!!
            val begin = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val outer = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.operation {
                    begin.await()
                    assertTrue(t.register.bind(session, t.item()))
                    completed.complete(Unit)
                    finish.await()
                }
            }
            var entered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive { entered = true }
            }
            begin.complete(Unit)
            completed.await()
            assertFalse(entered)
            assertEquals(1, t.requests)
            assertTrue(t.chapters.getChapterByMangaId(t.manga.id).single().read)
            finish.complete(Unit)
            outer.join()
            exclusive.join()
        }
    }
}
