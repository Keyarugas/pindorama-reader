package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.ConventionalBackupPolicy
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.app.di.AppBindings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.track.model.Track
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Date

/**
 * Public creation paths, real snapshot creators and SQLite;
 * publication provider and non-library data are synthetic.
 */
class BackupSnapshotCoordinationTest {
    private val coordinator = LibraryOperationCoordinator.shared
    private val options = BackupOptions(appSettings = false, sourceSettings = false, extensionStores = false)

    @BeforeEach
    fun setup() = mockkStatic(UniFile::class)

    @AfterEach
    fun cleanup() = unmockkStatic(UniFile::class)

    private class Target(val database: Database) {
        val mangas = MangaRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database)
        val categories = CategoryRepositoryImpl(database)
        val histories = HistoryRepositoryImpl(database)
        val tracks = TrackRepositoryImpl(database)
        val context = mockk<Context>()
        val uri = mockk<Uri>()
        val file = mockk<UniFile>()
        val output = ByteArrayOutputStream()
        val sources = mockk<SourcesBackupCreator>()
        val preferences = mockk<PreferenceBackupCreator>()
        val stores = mockk<ExtensionStoresBackupCreator>()
        val validator = mockk<BackupFileValidator>()
        var favoriteReads = 0
        var readEntryReads = 0
        var onHistory: suspend () -> Unit = {}
        var onGlobalCategories: suspend () -> Unit = {}
        var onSources: suspend () -> Unit = {}
        var captured = emptyList<BackupManga>()
        lateinit var manga: Manga
        lateinit var chapter: Chapter
        lateinit var category: Category
        private val repository = object : MangaRepository by mangas {
            override suspend fun getFavorites(): List<Manga> {
                favoriteReads++
                return mangas.getFavorites()
            }
            override suspend fun getReadMangaNotInLibrary(): List<Manga> {
                readEntryReads++
                return mangas.getReadMangaNotInLibrary()
            }
        }
        private val categoryRepository = object : CategoryRepository by categories {
            override suspend fun getAll(): List<Category> {
                onGlobalCategories()
                return categories.getAll()
            }
        }
        private val historyRepository = object : HistoryRepository by histories {
            override suspend fun getHistoryByMangaId(mangaId: Long): List<History> {
                onHistory()
                return histories.getHistoryByMangaId(mangaId)
            }
        }
        val creator = BackupCreator(
            false,
            context,
            ProtoBuf,
            GetFavorites(repository),
            BackupPreferences(InMemoryPreferenceStore()),
            repository,
            CategoriesBackupCreator(GetCategories(categoryRepository)),
            MangaBackupCreator(database, GetCategories(categoryRepository), GetHistory(historyRepository)),
            preferences, stores, sources, validator,
        )

        init {
            every { UniFile.fromUri(context, uri) } returns file
            every { file.isFile } returns true
            every { file.length() } returns 0L
            every { file.uri } returns uri
            every { uri.toString() } returns "content://synthetic/snapshot"
            every { file.openOutputStream() } returns output
            every { file.openInputStream() } answers { ByteArrayInputStream(output.toByteArray()) }
            coEvery { validator.validate(uri) } returns BackupFileValidator.Results(emptyList(), emptyList())
            coEvery { sources(any()) } coAnswers {
                captured = firstArg()
                onSources()
                emptyList()
            }
            every { preferences.createApp(any()) } returns listOf(
                BackupPreference("synthetic", BooleanPreferenceValue(true)),
            )
            coEvery { preferences.createSource(any()) } returns emptyList()
            coEvery { stores() } returns emptyList()
        }

        suspend fun seed() {
            manga = mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 1, url = "/m", title = "old")),
            ).single()
            mangas.update(MangaUpdate(manga.id, favorite = true))
            manga = mangas.getMangaById(manga.id)
            chapter = chapters.addAll(
                listOf(Chapter.create().copy(mangaId = manga.id, url = "/c", name = "old")),
            ).single()
            categories.insert(Category(0, "old category", 0, 0))
            category = categories.getAll().single { it.name == "old category" }
            mangas.setMangaCategories(manga.id, listOf(category.id))
            histories.upsertHistory(HistoryUpdate(chapter.id, Date(1000), 10))
            tracks.insert(Track(0, manga.id, 1, 10, null, "old track", 1.0, 10, 1, 0.0, "", 0, 0, false))
        }

        suspend fun replaceState() {
            mangas.update(MangaUpdate(manga.id, title = "restored"))
            chapters.update(ChapterUpdate(chapter.id, name = "restored chapter", read = true))
            categories.updateName(category.id, "restored category")
            histories.upsertHistory(HistoryUpdate(chapter.id, Date(2000), 20))
            tracks.insert(tracks.getTracksByMangaId(manga.id).single().copy(lastChapterRead = 5.0))
        }

        suspend fun decoded() = ConventionalBackupReader.decode(okio.Buffer().write(output.toByteArray()), ProtoBuf)
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
    fun `snapshot finishes library reads before exclusive and releases before publication`() = runBlocking {
        database { t ->
            val history = CompletableDeferred<Unit>()
            val finishHistory = CompletableDeferred<Unit>()
            val globalCategories = CompletableDeferred<Unit>()
            val finishCategories = CompletableDeferred<Unit>()
            val snapshotReady = CompletableDeferred<Unit>()
            val finishSources = CompletableDeferred<Unit>()
            t.onHistory = {
                history.complete(Unit)
                finishHistory.await()
            }
            t.onGlobalCategories = {
                globalCategories.complete(Unit)
                finishCategories.await()
            }
            t.onSources = {
                snapshotReady.complete(Unit)
                finishSources.await()
            }
            val backup = async(start = CoroutineStart.UNDISPATCHED) { t.creator.backup(t.uri, options) }
            history.await()
            var applied = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive {
                    t.replaceState()
                    applied = true
                }
            }
            assertFalse(applied)
            finishHistory.complete(Unit)
            globalCategories.await()
            assertFalse(applied)
            finishCategories.complete(Unit)
            snapshotReady.await()
            exclusive.join()
            assertTrue(applied)
            assertFalse(backup.isCompleted)
            verify(exactly = 0) { UniFile.fromUri(t.context, t.uri) }
            finishSources.complete(Unit)
            backup.await()
            val data = t.decoded()
            val manga = data.backupManga.single()
            assertEquals("old", manga.title)
            assertEquals("old", manga.chapters.single().name)
            assertFalse(manga.chapters.single().read)
            assertEquals(1f, manga.tracking.single().lastChapterRead)
            assertEquals(1000L, manga.history.single().lastRead)
            assertEquals(listOf(t.category.order), manga.categories)
            assertEquals("old category", data.backupCategories.single().name)
        }
    }

    @Test
    fun `conventional and protected creation waiting for exclusive read only post restore state`() = runBlocking {
        for (protected in listOf(false, true)) {
            database { t ->
                val release = CompletableDeferred<Unit>()
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.exclusive {
                        release.await()
                        t.replaceState()
                    }
                }
                val backup = async(start = CoroutineStart.UNDISPATCHED) {
                    if (protected) {
                        t.creator.backupProtected(t.uri, options, "synthetic password".toCharArray())
                    } else {
                        t.creator.backup(t.uri, options)
                    }
                }
                assertEquals(0, t.favoriteReads)
                assertFalse(backup.isCompleted)
                release.complete(Unit)
                exclusive.join()
                backup.await()
                val manga = t.captured.single()
                assertEquals("restored", manga.title)
                assertEquals("restored chapter", manga.chapters.single().name)
                assertTrue(manga.chapters.single().read)
                assertEquals(5f, manga.tracking.single().lastChapterRead)
                assertEquals(2000L, manga.history.single().lastRead)
                assertEquals(1, t.favoriteReads)
                assertEquals(1, t.readEntryReads)
            }
        }
    }

    @Test
    fun `policy checks the single admitted favorite and read entry collection used for conversion`() = runBlocking {
        database { t ->
            val read = t.mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 1, url = "/read", title = "read")),
            ).single()
            t.chapters.addAll(listOf(Chapter.create().copy(mangaId = read.id, url = "/read/c", read = true)))
            var checked = emptyList<Manga>()
            mockkObject(ConventionalBackupPolicy)
            try {
                every { ConventionalBackupPolicy.check(any(), any()) } answers {
                    checked = firstArg()
                    callOriginal()
                }
                t.creator.backup(t.uri, options)
                assertEquals(setOf(t.manga.id, read.id), checked.map { it.id }.toSet())
                assertEquals(checked.map { it.url }, t.captured.map { it.url })
                assertEquals(1, t.favoriteReads)
                assertEquals(1, t.readEntryReads)
                verify(exactly = 1) { ConventionalBackupPolicy.check(any(), options) }
            } finally {
                unmockkObject(ConventionalBackupPolicy)
            }
        }
    }

    @Test
    fun `settings only creation does not wait or read library during exclusive`() = runBlocking {
        database { t ->
            val release = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { release.await() } }
            t.creator.backup(t.uri, options.copy(libraryEntries = false, categories = false, appSettings = true))
            assertEquals(0, t.favoriteReads)
            assertEquals(0, t.readEntryReads)
            assertTrue(t.captured.isEmpty())
            release.complete(Unit)
            exclusive.join()
        }
    }

    @Test
    fun `cancel while awaiting admission performs no library reads or destination access`() = runBlocking {
        database { t ->
            val release = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { release.await() } }
            val backup = launch(start = CoroutineStart.UNDISPATCHED) { t.creator.backup(t.uri, options) }
            backup.cancelAndJoin()
            assertEquals(0, t.favoriteReads)
            assertEquals(0, t.readEntryReads)
            verify(exactly = 0) { UniFile.fromUri(t.context, t.uri) }
            release.complete(Unit)
            exclusive.join()
        }
    }

    @Test
    fun `cancel during snapshot releases participation without publishing`() = runBlocking {
        database { t ->
            val history = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            t.onHistory = {
                history.complete(Unit)
                release.await()
            }
            val backup = launch(start = CoroutineStart.UNDISPATCHED) { t.creator.backup(t.uri, options) }
            history.await()
            backup.cancelAndJoin()
            coordinator.exclusive { }
            verify(exactly = 0) { UniFile.fromUri(t.context, t.uri) }
        }
    }

    @Test
    fun `nested snapshot completes behind requested exclusive and does not release external lease`() = runBlocking {
        database { t ->
            val begin = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            val releaseOuter = CompletableDeferred<Unit>()
            val outer = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.operation {
                    begin.await()
                    t.creator.backup(t.uri, options)
                    completed.complete(Unit)
                    releaseOuter.await()
                }
            }
            var entered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
            begin.complete(Unit)
            completed.await()
            assertFalse(entered)
            releaseOuter.complete(Unit)
            outer.join()
            exclusive.join()
            assertTrue(entered)
        }
    }
}
