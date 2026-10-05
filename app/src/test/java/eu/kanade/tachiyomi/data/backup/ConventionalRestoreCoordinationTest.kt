package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.PreparedProtectedRestore
import eu.kanade.tachiyomi.data.backup.restore.ProtectedBackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.ProtectedRestorePreferences
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.download.DownloadCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack

/** Real application and SQLite; decoding, preferences, notifications and cache are controlled collaborators. */
class ConventionalRestoreCoordinationTest {
    private val coordinator = LibraryOperationCoordinator.shared
    private val libraryOptions = RestoreOptions(appSettings = false, sourceSettings = false, extensionStores = false)

    private class Target(val database: Database) {
        val mangas = MangaRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database)
        val tracks = TrackRepositoryImpl(database)
        val categories = GetCategories(CategoryRepositoryImpl(database))
        val preferences = InMemoryPreferenceStore()
        val preferenceRestorer = mockk<PreferenceRestorer>(relaxed = true)
        val extensionRestorer = mockk<ExtensionStoreRestorer>(relaxed = true)
        val notifier = mockk<BackupNotifier>(relaxed = true)
        val cache = mockk<DownloadCache>()
        val decoder = mockk<BackupDecoder>()
        val queue = mockk<DelayedTrackingStore>(relaxed = true)
        val uri = mockk<Uri>()
        val items = mutableListOf<DelayedTrackingStore.DelayedTrackingItem>()
        var decodes = 0
        var onCache: () -> Unit = {}
        val data = Backup(
            listOf(
                BackupManga(
                    1,
                    "/conventional",
                    "synthetic",
                    chapters = listOf(BackupChapter("/chapter", "synthetic", read = true)),
                    categories = listOf(0),
                ),
            ),
        ).apply { backupCategories = listOf(BackupCategory("synthetic category", 0)) }

        init {
            every { queue.getItems() } answers { items.toList() }
            every { cache.invalidateCache() } answers { onCache() }
            coEvery { decoder.decode(uri) } coAnswers {
                decodes++
                data
            }
        }

        fun manga(chapterRepository: ChapterRepository = chapters): MangaRestorer {
            val getChapters = GetChaptersByMangaId(chapterRepository)
            val fetch = FetchInterval(getChapters)
            return MangaRestorer(
                database, categories, GetMangaByUrlAndSourceId(mangas), getChapters,
                UpdateManga(mangas, fetch), GetTracks(tracks), InsertTrack(tracks), fetch, chapterRepository, tracks,
            )
        }

        fun conventional(chapterRepository: ChapterRepository = chapters) = BackupRestorer(
            notifier, false, mockk<Context>(), database, cache,
            CategoriesRestorer(database, categories, LibraryPreferences(preferences)),
            preferenceRestorer, extensionRestorer, manga(chapterRepository), decoder, queue,
        )

        fun protected(chapterRepository: ChapterRepository = chapters) = ProtectedBackupRestorer(
            CategoriesRestorer(database, categories, LibraryPreferences(preferences)),
            manga(chapterRepository),
            ProtectedRestorePreferences(preferences),
            database,
            queue,
        )

        fun paused(entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) =
            object : ChapterRepository by chapters {
                override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
                    entered.complete(Unit)
                    release.await()
                    return chapters.getChapterByMangaId(mangaId, applyScanlatorFilter)
                }
            }

        suspend fun imported() = mangas.getMangaByUrlAndSourceId("/conventional", 1)
    }

    private suspend fun database(block: suspend (Target) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.create(driver).await()
            block(Target(AppBindings.providesDatabase(driver)))
        }
    }

    @Test
    fun `restore drains participants and publishes generation after cache invalidation`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            val finishParticipant = CompletableDeferred<Unit>()
            val existing = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.operation { finishParticipant.await() }
            }
            val applying = CompletableDeferred<Unit>()
            val finishRestore = CompletableDeferred<Unit>()
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                t.conventional(t.paused(applying, finishRestore)).restore(t.uri, libraryOptions)
            }
            assertEquals(1, t.decodes)
            assertFalse(applying.isCompleted)
            assertNull(t.imported())
            finishParticipant.complete(Unit)
            existing.join()
            applying.await()
            val events = mutableListOf<String>()
            val next = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.operation {
                    events += "participant"
                    coordinator.generation
                }
            }
            assertFalse(next.isCompleted)
            t.onCache = {
                assertFalse(next.isCompleted)
                assertEquals(generation, coordinator.generation)
                events += "cache"
            }
            finishRestore.complete(Unit)
            restore.await()
            assertEquals(generation + 1, next.await())
            assertEquals(listOf("cache", "participant"), events)
            val manga = checkNotNull(t.imported())
            assertTrue(t.chapters.getChapterByMangaId(manga.id).single().read)
            assertEquals("synthetic category", t.categories.await(manga.id).single().name)
        }
    }

    @Test
    fun `conventional exclusive refuses protected application until it finishes`() = runBlocking {
        database { t ->
            val applying = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                t.conventional(t.paused(applying, release)).restore(t.uri, libraryOptions)
            }
            applying.await()
            val protectedData = PreparedProtectedRestore.validated(Backup(listOf(BackupManga(1, "/protected"))))
            val error = assertThrows<BackupException> { t.protected().restore(protectedData, libraryOptions) }
            assertEquals(BackupError.BUSY, error.error)
            assertNull(t.mangas.getMangaByUrlAndSourceId("/protected", 1))
            release.complete(Unit)
            restore.await()
            t.protected().restore(protectedData, libraryOptions)
            assertTrue(t.mangas.getMangaByUrlAndSourceId("/protected", 1) != null)
        }
    }

    @Test
    fun `protected exclusive refuses conventional application without altering categories or manga`() = runBlocking {
        database { t ->
            val applying = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val prepared = PreparedProtectedRestore.validated(Backup(listOf(BackupManga(1, "/protected"))))
            val restore = async(start = CoroutineStart.UNDISPATCHED) {
                t.protected(t.paused(applying, release)).restore(prepared, libraryOptions)
            }
            applying.await()
            val error = assertThrows<BackupException> { t.conventional().restore(t.uri, libraryOptions) }
            assertEquals(BackupError.BUSY, error.error)
            assertNull(t.imported())
            assertTrue(t.categories.await().none { it.name == "synthetic category" })
            release.complete(Unit)
            restore.await()
        }
    }

    @Test
    fun `pending tracking refuses library and categories without mutations or queue removal`() = runBlocking {
        database { t ->
            t.items += DelayedTrackingStore.DelayedTrackingItem(1, 5f)
            val items = t.items.toList()
            val categories = t.categories.await()
            for (options in listOf(libraryOptions, libraryOptions.copy(libraryEntries = false))) {
                val error = assertThrows<BackupException> { t.conventional().restore(t.uri, options) }
                assertEquals(BackupError.PENDING_TRACKING, error.error)
                assertEquals("PENDING_TRACKING", error.message)
                assertNull(error.cause)
                assertNull(t.imported())
                assertEquals(categories, t.categories.await())
                assertEquals(items, t.items)
                verify(exactly = 0) { t.queue.add(any(), any()) }
                verify(exactly = 0) { t.queue.remove(any()) }
                verify(exactly = 0) { t.cache.invalidateCache() }
            }
            // Model the normal consumer having completed its pending work, outside restore.
            t.items.clear()
            t.conventional().restore(t.uri, libraryOptions)
            assertTrue(t.imported() != null)
        }
    }

    @Test
    fun `settings only restore bypasses participants queue and generation`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            val release = CompletableDeferred<Unit>()
            val participant = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.operation { release.await() } }
            every { t.queue.getItems() } throws IllegalStateException("queue must not be read")
            t.data.backupExtensionStores =
                listOf(BackupExtensionStore("synthetic", "synthetic", null, "", "", null, true, null))
            t.conventional().restore(
                t.uri,
                RestoreOptions(libraryEntries = false, categories = false),
            )
            assertTrue(participant.isActive)
            assertEquals(generation, coordinator.generation)
            assertNull(t.imported())
            assertTrue(t.categories.await().none { it.name == "synthetic category" })
            coVerify(exactly = 1) { t.preferenceRestorer.restoreApp(any(), null) }
            coVerify(exactly = 1) { t.preferenceRestorer.restoreSource(any()) }
            coVerify(exactly = 1) { t.extensionRestorer.invoke(any()) }
            verify(exactly = 0) { t.queue.getItems() }
            verify(exactly = 0) { t.cache.invalidateCache() }
            release.complete(Unit)
            participant.join()
        }
    }

    @Test
    fun `cancel during drain leaves database and generation untouched and admission reusable`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            val categories = t.categories.await()
            val release = CompletableDeferred<Unit>()
            val participant = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.operation { release.await() } }
            val restore =
                launch(start = CoroutineStart.UNDISPATCHED) { t.conventional().restore(t.uri, libraryOptions) }
            restore.cancelAndJoin()
            assertNull(t.imported())
            assertEquals(categories, t.categories.await())
            assertEquals(generation, coordinator.generation)
            verify(exactly = 0) { t.queue.getItems() }
            coordinator.operation { }
            release.complete(Unit)
            participant.join()
            t.conventional().restore(t.uri, libraryOptions)
        }
    }

    @Test
    fun `cache failure preserves committed library and releases admission`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            t.onCache = { throw IllegalStateException("synthetic cache failure") }
            t.conventional().restore(t.uri, libraryOptions)
            assertTrue(t.imported() != null)
            assertEquals(generation + 1, coordinator.generation)
            coordinator.operation { }
            verify(exactly = 1) { t.notifier.showRestoreComplete(any(), any(), any(), any(), any()) }
        }
    }
}
