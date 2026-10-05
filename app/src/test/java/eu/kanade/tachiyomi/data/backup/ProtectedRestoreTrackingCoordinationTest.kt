package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.content.SharedPreferences
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.interactor.RetryDelayedTracking
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.PreparedProtectedRestore
import eu.kanade.tachiyomi.data.backup.restore.ProtectedBackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.ProtectedRestorePreferences
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.mockk.coEvery
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import tachiyomi.domain.track.repository.TrackRepository

/** Real TrackChapter, restore, SQLite and queue logic; synthetic remote service and in-memory SharedPreferences. */
class ProtectedRestoreTrackingCoordinationTest {
    private val coordinator = LibraryOperationCoordinator.shared

    private class Target(val database: Database) {
        val mangas = MangaRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database)
        val tracks = TrackRepositoryImpl(database)
        val categories = GetCategories(CategoryRepositoryImpl(database))
        val preferences = InMemoryPreferenceStore()
        val context = mockk<Context>()
        private val shared = mockk<SharedPreferences>()
        private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        private val values = mutableMapOf<String, Float>()
        val queue: DelayedTrackingStore
        val service = mockk<BaseTracker>()
        val manager = mockk<TrackerManager>()
        lateinit var manga: Manga
        lateinit var track: Track
        var requests = 0
        var remote: suspend () -> Unit = {}
        var afterQueueLookup: suspend () -> Unit = {}
        var beforeInsert: suspend () -> Unit = {}
        private val trackingRepository = object : TrackRepository by tracks {
            override suspend fun getTrackById(id: Long): Track? {
                val track = tracks.getTrackById(id)
                afterQueueLookup()
                return track
            }
            override suspend fun insert(track: Track) {
                beforeInsert()
                tracks.insert(track)
            }
        }
        val tracker: TrackChapter
        val retry: RetryDelayedTracking

        init {
            every { context.getSharedPreferences("tracking_queue", Context.MODE_PRIVATE) } returns shared
            every { shared.all } answers { values.toMap() }
            every { shared.getFloat(any(), any()) } answers { values[firstArg<String>()] ?: secondArg() }
            every { shared.edit() } returns editor
            every { editor.putFloat(any(), any()) } answers {
                values[firstArg()] = secondArg()
                editor
            }
            every { editor.remove(any()) } answers {
                values.remove(firstArg<String>())
                editor
            }
            queue = DelayedTrackingStore(context)
            every { manager.get(1) } returns service
            every { service.isLoggedIn } returns true
            coEvery { service.refresh(any()) } coAnswers {
                requests++
                remote()
                firstArg()
            }
            coEvery { service.update(any(), any()) } coAnswers { firstArg() }
            tracker = TrackChapter(GetTracks(tracks), manager, InsertTrack(trackingRepository), queue)
            retry = RetryDelayedTracking(queue, trackingRepository, tracker)
        }

        suspend fun seed() {
            manga = mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 1, url = "/existing", title = "synthetic")),
            ).single()
            tracks.insert(Track(0, manga.id, 1, 10, null, "synthetic", 0.0, 20, 1, 0.0, "", 0, 0, false))
            track = tracks.getTracksByMangaId(manga.id).single()
        }

        fun restorer(chapterRepository: ChapterRepository = chapters): ProtectedBackupRestorer {
            val getChapters = GetChaptersByMangaId(chapters)
            val fetch = FetchInterval(getChapters)
            return ProtectedBackupRestorer(
                CategoriesRestorer(database, categories, LibraryPreferences(preferences)),
                MangaRestorer(
                    database, categories, GetMangaByUrlAndSourceId(mangas), getChapters,
                    UpdateManga(mangas, fetch), GetTracks(tracks), InsertTrack(tracks), fetch,
                    chapterRepository, tracks,
                ),
                ProtectedRestorePreferences(preferences),
                database,
                queue,
            )
        }

        suspend fun prepared() = PreparedProtectedRestore.validated(Backup(listOf(BackupManga(1, "/new"))))
        suspend fun trackChapter() = tracker.await(context, manga.id, 5.0, setupJobOnFailure = false)
        suspend fun imported() = mangas.getMangaByUrlAndSourceId("/new", 1)
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
    fun `cancelled caller retains tracking admission through remote local insert and queue removal`() = runBlocking {
        database { t ->
            t.queue.add(t.track.id, 2.0)
            val requested = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            val inserting = CompletableDeferred<Unit>()
            val finishInsert = CompletableDeferred<Unit>()
            t.remote = {
                requested.complete(Unit)
                response.await()
            }
            t.beforeInsert = {
                inserting.complete(Unit)
                finishInsert.await()
            }
            val tracking = launch(start = CoroutineStart.UNDISPATCHED) { t.trackChapter() }
            requested.await()
            tracking.cancel()
            val prepared = t.prepared()
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                t.restorer().restore(prepared, RestoreOptions())
            }
            assertFalse(restore.isCompleted)
            assertNull(t.imported())
            response.complete(Unit)
            inserting.await()
            assertFalse(restore.isCompleted)
            assertNull(t.imported())
            assertEquals(1, t.queue.getItems().size)
            finishInsert.complete(Unit)
            tracking.join()
            restore.await()
            assertTrue(t.queue.getItems().isEmpty())
            assertEquals(5.0, t.tracks.getTracksByMangaId(t.manga.id).single().lastChapterRead)
            assertTrue(t.imported() != null)
        }
    }

    @Test
    fun `remote failure queues intent before waiting restore checks and refuses application`() = runBlocking {
        database { t ->
            val requested = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            t.remote = {
                requested.complete(Unit)
                response.await()
                throw IllegalStateException("synthetic remote failure")
            }
            val tracking = launch(start = CoroutineStart.UNDISPATCHED) { t.trackChapter() }
            requested.await()
            val prepared = t.prepared()
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                assertThrows<BackupException> { t.restorer().restore(prepared, RestoreOptions()) }
            }
            assertFalse(restore.isCompleted)
            response.complete(Unit)
            tracking.join()
            val error = restore.await()
            assertEquals(BackupError.PENDING_TRACKING, error.error)
            assertEquals("PENDING_TRACKING", error.message)
            assertNull(error.cause)
            assertNull(t.imported())
            assertEquals(listOf(DelayedTrackingStore.DelayedTrackingItem(t.track.id, 5f)), t.queue.getItems())
            assertEquals(t.manga, t.mangas.getMangaById(t.manga.id))
            coordinator.operation { }
        }
    }

    @Test
    fun `pending queue remains intact on refusal and normal tracking completion enables restore`() = runBlocking {
        database { t ->
            t.queue.add(t.track.id, 3.0)
            val items = t.queue.getItems()
            val categories = t.categories.await()
            repeat(2) {
                val error = assertThrows<BackupException> { t.restorer().restore(t.prepared(), RestoreOptions()) }
                assertEquals(BackupError.PENDING_TRACKING, error.error)
                assertNull(error.cause)
                assertEquals(items, t.queue.getItems())
                assertEquals(categories, t.categories.await())
                assertEquals(t.manga, t.mangas.getMangaById(t.manga.id))
                assertNull(t.imported())
                assertEquals(0, t.requests)
            }
            t.trackChapter()
            assertTrue(t.queue.getItems().isEmpty())
            t.restorer().restore(t.prepared(), RestoreOptions())
            assertTrue(t.imported() != null)
        }
    }

    @Test
    fun `tracking cannot read or queue new intent between empty queue check and restore application`() = runBlocking {
        database { t ->
            val applying = CompletableDeferred<Unit>()
            val finishRestore = CompletableDeferred<Unit>()
            val paused = object : ChapterRepository by t.chapters {
                override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
                    applying.complete(Unit)
                    finishRestore.await()
                    return t.chapters.getChapterByMangaId(mangaId, applyScanlatorFilter)
                }
            }
            t.remote = { throw IllegalStateException("synthetic") }
            val prepared = t.prepared()
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                t.restorer(paused).restore(prepared, RestoreOptions())
            }
            applying.await()
            val tracking = launch(start = CoroutineStart.UNDISPATCHED) { t.trackChapter() }
            assertEquals(0, t.requests)
            assertTrue(t.queue.getItems().isEmpty())
            assertFalse(tracking.isCompleted)
            finishRestore.complete(Unit)
            restore.await()
            tracking.join()
            assertTrue(t.imported() != null)
            assertEquals(0, t.requests)
            assertTrue(t.queue.getItems().isEmpty())
            assertEquals(t.track, t.tracks.getTracksByMangaId(t.manga.id).single())
        }
    }

    @Test
    fun `cancelled exclusive admission without application does not invalidate waiting tracking`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            val releaseParticipant = CompletableDeferred<Unit>()
            val participant = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.operation { releaseParticipant.await() }
            }
            var applied = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive { applied = true }
            }
            val tracking = launch(start = CoroutineStart.UNDISPATCHED) { t.trackChapter() }
            assertEquals(0, t.requests)
            assertFalse(tracking.isCompleted)
            exclusive.cancel()
            exclusive.join()
            assertFalse(applied)
            assertEquals(generation, coordinator.generation)
            tracking.join()
            assertEquals(1, t.requests)
            assertEquals(5.0, t.tracks.getTracksByMangaId(t.manga.id).single().lastChapterRead)
            assertTrue(t.queue.getItems().isEmpty())
            releaseParticipant.complete(Unit)
            participant.join()
        }
    }

    @Test
    fun `nested tracking completes behind pending exclusive without releasing outer admission`() = runBlocking {
        database { t ->
            val entered = CompletableDeferred<Unit>()
            val startTracking = CompletableDeferred<Unit>()
            val tracked = CompletableDeferred<Unit>()
            val releaseOuter = CompletableDeferred<Unit>()
            val outer = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.operation {
                    entered.complete(Unit)
                    startTracking.await()
                    t.trackChapter()
                    tracked.complete(Unit)
                    releaseOuter.await()
                }
            }
            entered.await()
            var exclusiveEntered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive { exclusiveEntered = true }
            }
            startTracking.complete(Unit)
            tracked.await()
            assertFalse(exclusiveEntered)
            releaseOuter.complete(Unit)
            outer.join()
            exclusive.join()
            assertTrue(exclusiveEntered)
            assertEquals(5.0, t.tracks.getTracksByMangaId(t.manga.id).single().lastChapterRead)
        }
    }

    @Test
    fun `visual only restore does not consult or discard pending queue`() = runBlocking {
        database { t ->
            t.queue.add(t.track.id, 3.0)
            val items = t.queue.getItems()
            val generation = coordinator.generation
            t.restorer().restore(
                t.prepared(),
                RestoreOptions(categories = false, libraryEntries = false, appSettings = false),
            )
            assertEquals(items, t.queue.getItems())
            assertNull(t.imported())
            assertEquals(generation, coordinator.generation)
        }
    }

    @Test
    fun `worker materialization retains lease even if another participant empties queue`() = runBlocking {
        database { t ->
            t.queue.add(t.track.id, 5.0)
            val materialized = CompletableDeferred<Unit>()
            val apply = CompletableDeferred<Unit>()
            t.afterQueueLookup = {
                materialized.complete(Unit)
                apply.await()
            }
            val generation = coordinator.generation
            val worker = async(start = CoroutineStart.UNDISPATCHED) { t.retry.await(t.context, generation) }
            materialized.await()
            // A normal participant can empty the queue, but cannot release the worker's lease.
            coordinator.operation { t.queue.remove(t.track.id) }
            val prepared = t.prepared()
            val restore =
                async(start = CoroutineStart.UNDISPATCHED) { t.restorer().restore(prepared, RestoreOptions()) }
            assertFalse(restore.isCompleted)
            assertEquals(generation, coordinator.generation)
            assertEquals(0, t.requests)
            apply.complete(Unit)
            assertTrue(worker.await())
            restore.await()
            assertEquals(1, t.requests)
            assertEquals(generation + 1, coordinator.generation)
            assertTrue(t.queue.getItems().isEmpty())
            assertTrue(t.imported() != null)
        }
    }

    @Test
    fun `worker born before restore cannot materialize queue with a new generation`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            coordinator.exclusive { }
            t.queue.add(t.track.id, 5.0)
            val expected = t.queue.getItems()
            t.afterQueueLookup = { error("stale worker must not look up tracks") }
            assertFalse(t.retry.await(t.context, generation))
            assertEquals(0, t.requests)
            assertEquals(expected, t.queue.getItems())
            assertEquals(t.track, t.tracks.getTracksByMangaId(t.manga.id).single())
        }
    }
}
