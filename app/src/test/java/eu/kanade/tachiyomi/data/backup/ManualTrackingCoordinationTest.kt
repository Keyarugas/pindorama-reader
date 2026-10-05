package eu.kanade.tachiyomi.data.backup

import android.content.Context
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.download.interactor.DeleteDownload
import eu.kanade.domain.track.interactor.ManualTracking
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.interactor.TrackReadChapters
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/** SQLite state is real; only the remote tracker and filesystem effects are synthetic. */
class ManualTrackingCoordinationTest {
    private val coordinator = LibraryOperationCoordinator.shared
    private abstract class SyntheticTracker : BaseTracker(1, "synthetic"), DeletableTracker

    private class Target(database: Database) {
        val mangas = MangaRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database)
        val tracks = TrackRepositoryImpl(database)
        val context = mockk<Context>()
        val queue = mockk<DelayedTrackingStore>(relaxed = true)
        val service = mockk<SyntheticTracker>()
        val manager = mockk<TrackerManager>()
        val refresh = mockk<RefreshTracks>()
        var reads = 0
        var writes = 0
        var requests = 0
        var remote: suspend () -> Unit = {}
        var beforeInsert: suspend () -> Unit = {}
        lateinit var manga: Manga
        lateinit var track: Track
        private val repository = object : TrackRepository by tracks {
            override suspend fun getTrackById(id: Long): Track? {
                reads++
                return tracks.getTrackById(id)
            }
            override suspend fun getTracksByMangaId(mangaId: Long): List<Track> {
                reads++
                return tracks.getTracksByMangaId(mangaId)
            }
            override suspend fun insert(track: Track) {
                beforeInsert()
                writes++
                tracks.insert(track)
            }
            override suspend fun delete(mangaId: Long, trackerId: Long) {
                writes++
                tracks.delete(mangaId, trackerId)
            }
        }
        private val chapterRepository = object : ChapterRepository by chapters {
            override suspend fun getChapterById(id: Long): Chapter? {
                reads++
                return chapters.getChapterById(id)
            }
        }
        private val insert = InsertTrack(repository)
        val manual = ManualTracking(GetManga(mangas), repository, manager, DeleteTrack(repository))
        private val trackChapter = TrackChapter(GetTracks(repository), manager, insert, queue)
        val read = TrackReadChapters(
            mangas,
            chapterRepository,
            SetReadStatus(
                DownloadPreferences(InMemoryPreferenceStore()),
                mockk<DeleteDownload>(),
                mangas,
                chapterRepository,
            ),
            refresh,
            GetTracks(repository),
            trackChapter,
        )

        init {
            every { manager.get(1) } returns service
            every { service.isLoggedIn } returns true
            coEvery { service.setRemoteStatus(any(), any()) } coAnswers {
                requests++
                remote()
                val current = firstArg<eu.kanade.tachiyomi.data.database.models.Track>()
                current.status = secondArg()
                insert.await(current.toDomainTrack()!!)
            }
            coEvery { service.delete(any()) } coAnswers {
                requests++
                remote()
            }
            coEvery { service.refresh(any()) } coAnswers {
                requests++
                remote()
                firstArg()
            }
            coEvery { service.update(any(), any()) } coAnswers { firstArg() }
            coEvery { refresh.await(any(), any()) } returns emptyList()
        }

        suspend fun seed() {
            manga = mangas.insertNetworkManga(listOf(Manga.create().copy(source = 1, url = "/synthetic"))).single()
            chapters.addAll(listOf(Chapter.create().copy(mangaId = manga.id, url = "/chapter", chapterNumber = 5.0)))
            tracks.insert(Track(0, manga.id, 1, 10, null, "synthetic", 0.0, 20, 1, 0.0, "/track", 0, 0, false))
            track = tracks.getTracksByMangaId(manga.id).single()
        }

        suspend fun selection() = chapters.getChapterByMangaId(manga.id)
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
    fun `stale editor and unlink confirmation cannot read write or request`() = runBlocking {
        database { t ->
            val target = t.manual.loadTarget(t.track, coordinator.generation)!!
            t.reads = 0
            coordinator.exclusive { }
            assertFalse(t.manual.update(target) { service, current -> service.setRemoteStatus(current, 2) })
            assertFalse(t.manual.unlink(target, removeRemote = true))
            assertEquals(0, t.reads)
            assertEquals(0, t.writes)
            assertEquals(0, t.requests)
            assertEquals(t.track, t.tracks.getTracksByMangaId(t.manga.id).single())
        }
    }

    @Test
    fun `changed remote association is refused even without restore`() = runBlocking {
        database { t ->
            val target = t.manual.loadTarget(t.track, coordinator.generation)!!
            t.tracks.insert(t.track.copy(remoteId = 20))
            assertFalse(t.manual.update(target) { service, current -> service.setRemoteStatus(current, 2) })
            assertFalse(t.manual.unlink(target, removeRemote = false))
            assertEquals(0, t.writes)
            assertEquals(0, t.requests)
            assertEquals(20L, t.tracks.getTracksByMangaId(t.manga.id).single().remoteId)
        }
    }

    @Test
    fun `admitted edit retains lease through remote response and final insert`() = runBlocking {
        database { t ->
            val loaded = t.manual.loadTarget(t.track, coordinator.generation)!!
            val target = loaded.copy(track = loaded.track.copy(score = 99.0))
            val requested = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            val inserting = CompletableDeferred<Unit>()
            val inserted = CompletableDeferred<Unit>()
            t.remote = {
                requested.complete(Unit)
                response.await()
            }
            t.beforeInsert = {
                inserting.complete(Unit)
                inserted.await()
            }
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                t.manual.update(target) { service, current -> service.setRemoteStatus(current, 2) }
            }
            requested.await()
            var entered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
            assertFalse(entered)
            response.complete(Unit)
            inserting.await()
            assertFalse(entered)
            inserted.complete(Unit)
            assertTrue(call.await())
            exclusive.join()
            assertEquals(2L, t.tracks.getTracksByMangaId(t.manga.id).single().status)
            assertEquals(0.0, t.tracks.getTracksByMangaId(t.manga.id).single().score)
        }
    }

    @Test
    fun `fresh unlink removes only the revalidated association`() = runBlocking {
        database { t ->
            val target = t.manual.loadTarget(t.track, coordinator.generation)!!
            assertTrue(t.manual.unlink(target, removeRemote = false))
            assertTrue(t.tracks.getTracksByMangaId(t.manga.id).isEmpty())
            assertEquals(1, t.writes)
        }
    }

    @Test
    fun `unlink keeps restore waiting until remote removal finishes`() = runBlocking {
        database { t ->
            val target = t.manual.loadTarget(t.track, coordinator.generation)!!
            val requested = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            t.remote = {
                requested.complete(Unit)
                response.await()
            }
            val call = async(start = CoroutineStart.UNDISPATCHED) { t.manual.unlink(target, true) }
            requested.await()
            assertTrue(t.tracks.getTracksByMangaId(t.manga.id).isEmpty())
            var entered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
            assertFalse(entered)
            response.complete(Unit)
            assertTrue(call.await())
            exclusive.join()
            assertEquals(1, t.requests)
        }
    }

    @Test
    fun `discarded mark read produces no refresh tracking queue or chapter effect`() = runBlocking {
        database { t ->
            val selected = t.selection()
            val generation = coordinator.generation
            coordinator.exclusive { }
            assertEquals(null, t.read.prepare(t.manga, selected, true, true, generation))
            assertEquals(0, t.reads)
            assertEquals(0, t.writes)
            assertEquals(0, t.requests)
            assertFalse(t.selection().single().read)
            coVerify(exactly = 0) { t.refresh.await(any(), any()) }
            io.mockk.verify(exactly = 0) { t.queue.add(any(), any()) }
        }
    }

    @Test
    fun `restore between mark read and automatic tracking discards the derived intent`() = runBlocking {
        database { t ->
            val intent = t.read.prepare(t.manga, t.selection(), true, true, coordinator.generation)!!.intent
            assertTrue(t.selection().single().read)
            t.reads = 0
            coordinator.exclusive { }
            assertFalse(t.read.confirm(t.context, intent))
            assertEquals(0, t.reads)
            assertEquals(0, t.requests)
            assertEquals(0, t.writes)
            io.mockk.verify(exactly = 0) { t.queue.add(any(), any()) }
        }
    }

    @Test
    fun `snackbar wait owns no lease and stale confirmation cannot update tracking`() = runBlocking {
        database { t ->
            val intent = t.read.prepare(t.manga, t.selection(), true, true, coordinator.generation)!!.intent
            val confirmation = CompletableDeferred<Unit>()
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                confirmation.await()
                t.read.confirm(t.context, intent)
            }
            coordinator.exclusive { }
            t.reads = 0
            confirmation.complete(Unit)
            assertFalse(call.await())
            assertEquals(0, t.reads)
            assertEquals(0, t.requests)
            assertEquals(t.track, t.tracks.getTracksByMangaId(t.manga.id).single())
        }
    }

    @Test
    fun `current mark read confirmation updates progress normally`() = runBlocking {
        database { t ->
            val intent = t.read.prepare(t.manga, t.selection(), true, true, coordinator.generation)!!.intent
            assertTrue(t.read.confirm(t.context, intent))
            assertEquals(1, t.requests)
            assertEquals(5.0, t.tracks.getTracksByMangaId(t.manga.id).single().lastChapterRead)
        }
    }
}
