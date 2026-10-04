package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.ToggleMangaFavorite
import eu.kanade.domain.manga.interactor.ToggleMangaPrivacy
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.core.security.PrivateContentSession
import eu.kanade.tachiyomi.data.cache.CoverCache
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
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/** SQLite, real flags/defaults/membership/privacy logic and synthetic tracking/cache effects. */
class ManualMangaStateCoordinationTest {
    private val coordinator = LibraryOperationCoordinator.shared
    private enum class Kind {
        UNREAD,
        DOWNLOADED,
        BOOKMARK,
        DISPLAY,
        SORT,
        DEFAULT,
        DEFAULT_ALL,
        DEFAULT_CURRENT,
        FAVORITE,
        PRIVACY,
    }

    private class Target(val database: Database) {
        val mangas = MangaRepositoryImpl(database)
        val preferences = LibraryPreferences(InMemoryPreferenceStore())
        val tracking = mockk<AddTracks>()
        val sources = mockk<SourceManager>()
        val source = mockk<Source>()
        val covers = mockk<CoverCache>()
        val session = PrivateContentSession()
        lateinit var manga: Manga
        lateinit var second: Manga
        var reads = 0
        var tracks = 0
        var coverDeletes = 0
        val updates = mutableListOf<MangaUpdate>()
        var onRead: suspend () -> Unit = {}
        var onWrite: suspend () -> Unit = {}
        var onTracking: suspend () -> Unit = {}
        private val repository = object : MangaRepository by mangas {
            override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
                reads++
                onRead()
                return mangas.getMangaByUrlAndSourceId(url, sourceId)
            }
            override suspend fun getFavorites(): List<Manga> {
                reads++
                onRead()
                return mangas.getFavorites()
            }
            override suspend fun update(update: MangaUpdate): Boolean {
                onWrite()
                updates.add(update)
                return mangas.update(update)
            }
        }
        val flags = SetMangaChapterFlags(repository)
        val defaults = SetMangaDefaultChapterFlags(preferences, flags, GetFavorites(repository), repository)
        val favorite = ToggleMangaFavorite(repository, defaults, tracking, sources, covers)
        val privacy = ToggleMangaPrivacy(repository)

        init {
            session.authenticationSucceeded(session.beginAuthentication()!!)
            preferences.filterChapterByRead.set(Manga.CHAPTER_SHOW_UNREAD)
            preferences.displayChapterByNameOrNumber.set(Manga.CHAPTER_DISPLAY_NUMBER)
            coEvery { sources.getOrStub(any()) } returns source
            every { covers.deleteFromCache(any(), true) } answers {
                coverDeletes++
                0
            }
            coEvery { tracking.bindEnhancedTrackers(any(), any()) } coAnswers {
                LibraryOperationCoordinator.shared.operation {
                    tracks++
                    onTracking()
                }
            }
        }

        suspend fun seed() {
            val entries = mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(source = 1, url = "/first", title = "old"),
                    Manga.create().copy(source = 1, url = "/second", title = "second", favorite = true),
                ),
            )
            manga = entries[0]
            second = entries[1]
        }

        suspend fun current() = mangas.getMangaById(manga.id)

        suspend fun execute(kind: Kind) {
            when (kind) {
                Kind.UNREAD -> flags.awaitSetUnreadFilter(manga, Manga.CHAPTER_SHOW_UNREAD)
                Kind.DOWNLOADED -> flags.awaitSetDownloadedFilter(manga, Manga.CHAPTER_SHOW_DOWNLOADED)
                Kind.BOOKMARK -> flags.awaitSetBookmarkFilter(manga, Manga.CHAPTER_SHOW_BOOKMARKED)
                Kind.DISPLAY -> flags.awaitSetDisplayMode(manga, Manga.CHAPTER_DISPLAY_NUMBER)
                Kind.SORT -> flags.awaitSetSortingModeOrFlipOrder(manga, Manga.CHAPTER_SORTING_NUMBER)
                Kind.DEFAULT -> defaults.await(manga, onlyIfNotFavorite = true)
                Kind.DEFAULT_ALL -> defaults.awaitAll()
                Kind.DEFAULT_CURRENT -> defaults.awaitSetCurrentAsDefault(manga, applyToExisting = true)
                Kind.FAVORITE -> favorite.await(manga)
                Kind.PRIVACY -> privacy.await(manga, session)
            }
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
    fun `stale intents have no reads or effects`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val release = CompletableDeferred<Unit>()
                val exclusive =
                    launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { release.await() } }
                val call = async(start = CoroutineStart.UNDISPATCHED) { t.execute(kind) }
                assertFalse(call.isCompleted)
                assertEquals(0, t.reads)
                release.complete(Unit)
                exclusive.join()
                call.await()
                assertEquals(0, t.reads)
                assertTrue(t.updates.isEmpty())
                assertEquals(0, t.tracks)
                assertEquals(0, t.coverDeletes)
                assertEquals(t.manga, t.current())
            }
        }
    }

    @Test
    fun `filters and display modify only their mask using persisted flags`() = runBlocking {
        val currentFlags = listOf(
            Manga.CHAPTER_SHOW_READ,
            Manga.CHAPTER_SHOW_NOT_DOWNLOADED,
            Manga.CHAPTER_SHOW_NOT_BOOKMARKED,
            Manga.CHAPTER_SORTING_UPLOAD_DATE,
            Manga.CHAPTER_SORT_ASC,
        ).fold(0L) { flags, flag -> flags or flag }
        val changes = listOf(
            Triple(Kind.UNREAD, Manga.CHAPTER_UNREAD_MASK, Manga.CHAPTER_SHOW_UNREAD),
            Triple(Kind.DOWNLOADED, Manga.CHAPTER_DOWNLOADED_MASK, Manga.CHAPTER_SHOW_DOWNLOADED),
            Triple(Kind.BOOKMARK, Manga.CHAPTER_BOOKMARKED_MASK, Manga.CHAPTER_SHOW_BOOKMARKED),
            Triple(Kind.DISPLAY, Manga.CHAPTER_DISPLAY_MASK, Manga.CHAPTER_DISPLAY_NUMBER),
        )
        for ((kind, mask, flag) in changes) {
            database { t ->
                t.mangas.update(MangaUpdate(t.manga.id, chapterFlags = currentFlags))
                t.execute(kind)
                assertEquals(currentFlags and mask.inv() or flag, t.current().chapterFlags)
                assertEquals(MangaUpdate(t.manga.id, chapterFlags = t.current().chapterFlags), t.updates.single())
            }
        }
    }

    @Test
    fun `sorting uses current state`() = runBlocking {
        database { t ->
            val flags = Manga.CHAPTER_SORTING_NUMBER or Manga.CHAPTER_SORT_ASC or Manga.CHAPTER_SHOW_READ
            t.mangas.update(MangaUpdate(t.manga.id, chapterFlags = flags))
            t.flags.awaitSetSortingModeOrFlipOrder(t.manga, Manga.CHAPTER_SORTING_NUMBER)
            assertEquals(flags and Manga.CHAPTER_SORT_DIR_MASK.inv(), t.current().chapterFlags)
            t.flags.awaitSetSortingModeOrFlipOrder(t.manga, Manga.CHAPTER_SORTING_UPLOAD_DATE)
            assertEquals(
                Manga.CHAPTER_SORTING_UPLOAD_DATE or Manga.CHAPTER_SORT_ASC or Manga.CHAPTER_SHOW_READ,
                t.current().chapterFlags,
            )
        }
    }

    @Test
    fun `defaults revalidate membership and allow explicit reset`() = runBlocking {
        database { t ->
            t.mangas.update(MangaUpdate(t.manga.id, favorite = true, chapterFlags = Manga.CHAPTER_SHOW_READ))
            t.defaults.await(t.manga, onlyIfNotFavorite = true)
            assertTrue(t.updates.isEmpty())
            assertEquals(Manga.CHAPTER_SHOW_READ, t.current().chapterFlags)
            t.defaults.await(t.manga)
            assertEquals(1, t.updates.size)
            assertEquals(Manga.CHAPTER_SHOW_UNREAD, t.current().unreadFilterRaw)
        }
    }

    @Test
    fun `defaults batch retains participation through all favorites`() = runBlocking {
        database { t ->
            t.mangas.update(MangaUpdate(t.manga.id, favorite = true))
            val firstWritten = CompletableDeferred<Unit>()
            val finishSecond = CompletableDeferred<Unit>()
            var writes = 0
            t.onWrite = {
                writes++
                if (writes == 2) {
                    firstWritten.complete(Unit)
                    finishSecond.await()
                }
            }
            val batch = launch(start = CoroutineStart.UNDISPATCHED) { t.defaults.awaitAll() }
            firstWritten.await()
            assertEquals(1, t.updates.size)
            var entered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive { entered = true }
            }
            assertFalse(entered)
            finishSecond.complete(Unit)
            batch.join()
            exclusive.join()
            assertTrue(entered)
            assertEquals(setOf(t.manga.id, t.second.id), t.updates.map { it.id }.toSet())
        }
    }

    @Test
    fun `browse toggle preserves current unrelated fields`() = runBlocking {
        database { t ->
            t.mangas.update(
                MangaUpdate(
                    t.manga.id,
                    favorite = true,
                    dateAdded = 99,
                    isPrivate = true,
                    notes = "current notes",
                    title = "current title",
                    chapterFlags = 0x100123,
                    viewerFlags = 5,
                ),
            )
            val current = t.current()
            t.favorite.await(t.manga)
            assertContent(current.copy(favorite = false, dateAdded = 0), t.current())
            assertEquals(MangaUpdate(t.manga.id, favorite = false, dateAdded = 0), t.updates.single())
            assertEquals(0, t.tracks)
        }
        database { t ->
            t.favorite.await(t.manga)
            assertTrue(t.current().favorite)
            assertTrue(t.current().dateAdded > 0)
            assertEquals(1, t.tracks)
        }
    }

    @Test
    fun `browse owns participation through tracking and membership`() = runBlocking {
        database { t ->
            val tracking = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            t.onTracking = {
                tracking.complete(Unit)
                finish.await()
            }
            val call = launch(start = CoroutineStart.UNDISPATCHED) { t.favorite.await(t.manga) }
            tracking.await()
            var entered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive { entered = true }
            }
            assertFalse(entered)
            assertFalse(t.current().favorite)
            finish.complete(Unit)
            call.join()
            exclusive.join()
            assertTrue(t.current().favorite)
            assertTrue(entered)
        }
    }

    @Test
    fun `privacy uses current private state and revalidates private session`() = runBlocking {
        database { t ->
            t.mangas.update(MangaUpdate(t.manga.id, isPrivate = true, notes = "current", chapterFlags = 73))
            val current = t.current()
            t.session.lock()
            assertEquals(ToggleMangaPrivacy.Result.Unchanged, t.privacy.await(t.manga, t.session))
            assertTrue(t.updates.isEmpty())
            t.session.authenticationSucceeded(t.session.beginAuthentication()!!)
            assertEquals(ToggleMangaPrivacy.Result.Changed(false), t.privacy.await(t.manga, t.session))
            assertContent(current.copy(isPrivate = false), t.current())
            assertEquals(MangaUpdate(t.manga.id, isPrivate = false), t.updates.single())
            t.session.lock()
            assertEquals(ToggleMangaPrivacy.Result.Changed(true), t.privacy.await(t.manga, t.session))
        }
    }

    @Test
    fun `privacy authorization ignores expected private state when current manga is public`() = runBlocking {
        database { t ->
            val current = t.current()
            t.session.lock()
            assertEquals(
                ToggleMangaPrivacy.Result.Changed(true),
                t.privacy.await(t.manga.copy(isPrivate = true), t.session),
            )
            assertContent(current.copy(isPrivate = true), t.current())
            assertEquals(MangaUpdate(t.manga.id, isPrivate = true), t.updates.single())
        }
    }

    @Test
    fun `privacy action expires while dispatched before interactor entry`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            val scheduled = CompletableDeferred<Unit>()
            val enterInteractor = CompletableDeferred<Unit>()
            val call = async {
                scheduled.complete(Unit)
                enterInteractor.await()
                t.privacy.await(t.manga, t.session, generation)
            }
            scheduled.await()
            coordinator.exclusive { t.mangas.update(MangaUpdate(t.manga.id, isPrivate = true)) }
            enterInteractor.complete(Unit)
            assertEquals(ToggleMangaPrivacy.Result.Unchanged, call.await())
            assertEquals(0, t.reads)
            assertTrue(t.updates.isEmpty())
            assertTrue(t.current().isPrivate)
        }
    }

    @Test
    fun `old generation cannot change restored state`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            coordinator.exclusive { t.mangas.update(MangaUpdate(t.manga.id, isPrivate = true, chapterFlags = 73)) }
            assertEquals(ToggleMangaPrivacy.Result.Unchanged, t.privacy.await(t.manga, t.session, generation))
            t.flags.awaitSetUnreadFilter(t.manga, Manga.CHAPTER_SHOW_UNREAD, generation)
            t.defaults.await(t.manga, generation = generation)
            t.defaults.awaitAll(generation)
            assertFalse(t.defaults.awaitSetCurrentAsDefault(t.manga, applyToExisting = true, generation = generation))
            t.favorite.await(t.manga, generation)
            assertEquals(0, t.reads)
            assertTrue(t.updates.isEmpty())
            assertTrue(t.current().isPrivate)
            assertEquals(Manga.CHAPTER_SHOW_UNREAD, t.preferences.filterChapterByRead.get())
            assertEquals(Manga.CHAPTER_DISPLAY_NUMBER, t.preferences.displayChapterByNameOrNumber.get())
        }
    }

    @Test
    fun `saving defaults uses current flags`() = runBlocking {
        database { t ->
            t.mangas.update(MangaUpdate(t.manga.id, chapterFlags = Manga.CHAPTER_SHOW_READ))
            assertTrue(t.defaults.awaitSetCurrentAsDefault(t.manga, applyToExisting = true))
            assertEquals(Manga.CHAPTER_SHOW_READ, t.preferences.filterChapterByRead.get())
            assertEquals(Manga.CHAPTER_SHOW_READ, t.mangas.getMangaById(t.second.id).unreadFilterRaw)
        }
    }

    @Test
    fun `saving defaults rejects mismatched identity without changing preferences`() = runBlocking {
        database { t ->
            assertFalse(t.defaults.awaitSetCurrentAsDefault(t.manga.copy(id = t.second.id), applyToExisting = true))
            assertEquals(Manga.CHAPTER_SHOW_UNREAD, t.preferences.filterChapterByRead.get())
            assertEquals(Manga.CHAPTER_DISPLAY_NUMBER, t.preferences.displayChapterByNameOrNumber.get())
            assertTrue(t.updates.isEmpty())
        }
    }

    @Test
    fun `admitted intentions block exclusive through requery and final write`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val reading = CompletableDeferred<Unit>()
                val finishRead = CompletableDeferred<Unit>()
                val writing = CompletableDeferred<Unit>()
                val finishWrite = CompletableDeferred<Unit>()
                t.onRead = {
                    reading.complete(Unit)
                    finishRead.await()
                }
                t.onWrite = {
                    writing.complete(Unit)
                    finishWrite.await()
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
                finishWrite.complete(Unit)
                call.join()
                exclusive.join()
                assertTrue(entered)
            }
        }
    }

    @Test
    fun `snapshot identity mismatch prevents local or derived effects`() = runBlocking {
        for (kind in Kind.entries.filterNot { it == Kind.DEFAULT_ALL }) {
            for (identity in listOf("id", "source", "url")) {
                database { t ->
                    t.manga = when (identity) {
                        "id" -> t.manga.copy(id = t.manga.id + 100)
                        "source" -> t.manga.copy(source = 2)
                        else -> t.manga.copy(url = "/absent")
                    }
                    t.execute(kind)
                    assertTrue(t.updates.isEmpty())
                    assertEquals(0, t.tracks)
                    assertEquals(0, t.coverDeletes)
                }
            }
        }
    }

    @Test
    fun `nested intentions retain external lease with exclusive already pending`() = runBlocking {
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
                assertTrue(t.updates.isNotEmpty())
                release.complete(Unit)
                outer.join()
                exclusive.join()
                assertTrue(entered)
            }
        }
    }

    @Test
    fun `cancellation at admission or requery prevents writes`() = runBlocking {
        for (kind in listOf(Kind.UNREAD, Kind.FAVORITE, Kind.PRIVACY)) {
            database { t ->
                val finish = CompletableDeferred<Unit>()
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.exclusive { finish.await() }
                }
                val call = async(start = CoroutineStart.UNDISPATCHED) { t.execute(kind) }
                call.cancelAndJoin()
                assertTrue(call.isCancelled)
                assertEquals(0, t.reads)
                assertTrue(t.updates.isEmpty())
                finish.complete(Unit)
                exclusive.join()
            }
            database { t ->
                val reading = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                t.onRead = {
                    reading.complete(Unit)
                    finish.await()
                }
                val call = async(start = CoroutineStart.UNDISPATCHED) { t.execute(kind) }
                reading.await()
                call.cancelAndJoin()
                assertTrue(call.isCancelled)
                assertTrue(t.updates.isEmpty())
                coordinator.exclusive { }
            }
        }
    }

    @Test
    fun `cancelled exclusive drain does not invalidate waiting intentions`() = runBlocking {
        for (kind in Kind.entries) {
            database { t ->
                val finish = CompletableDeferred<Unit>()
                val participant =
                    launch(start = CoroutineStart.UNDISPATCHED) { coordinator.operation { finish.await() } }
                val generation = coordinator.generation
                val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.exclusive { error("unexpected application") }
                }
                val call = async(start = CoroutineStart.UNDISPATCHED) { t.execute(kind) }
                exclusive.cancelAndJoin()
                assertEquals(generation, coordinator.generation)
                call.await()
                assertTrue(t.updates.isNotEmpty())
                finish.complete(Unit)
                participant.join()
            }
        }
    }
}
