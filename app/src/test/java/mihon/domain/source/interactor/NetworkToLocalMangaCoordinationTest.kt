package mihon.domain.source.interactor

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingSource
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ResolvableSource
import eu.kanade.tachiyomi.source.online.UriType
import eu.kanade.tachiyomi.ui.deeplink.DeepLinkViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mihon.app.di.AppBindings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.source.SourceLatestPagingSource
import tachiyomi.data.source.SourcePopularPagingSource
import tachiyomi.data.source.SourceSearchPagingSource
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/** Real local application and SQLite; remote responses are synthetic and gated deterministically. */
class NetworkToLocalMangaCoordinationTest {
    private val coordinator = LibraryOperationCoordinator.shared

    private class Target(val database: Database) {
        val mangas = MangaRepositoryImpl(database)
        var reads = 0
        var applications = 0
        var onRead: suspend () -> Unit = {}
        var onWrite: suspend () -> Unit = {}
        val repository = object : MangaRepository by mangas {
            override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
                reads++
                onRead()
                return mangas.getMangaByUrlAndSourceId(url, sourceId)
            }

            override suspend fun insertNetworkManga(manga: List<Manga>): List<Manga> {
                onWrite()
                applications++
                return mangas.insertNetworkManga(manga)
            }
        }
        val apply = NetworkToLocalManga(repository)
        lateinit var manga: Manga
        val remote = Manga.create().copy(
            source = 1,
            url = "/synthetic",
            title = "remote title",
            author = "remote author",
            artist = "remote artist",
            description = "remote description",
            genre = listOf("synthetic genre"),
            status = 2,
            thumbnailUrl = "synthetic-cover",
            initialized = true,
            memo = JsonObject(mapOf("synthetic" to JsonPrimitive("remote"))),
        )

        suspend fun seed() {
            manga = mangas.insertNetworkManga(
                listOf(Manga.create().copy(source = 1, url = remote.url, title = "local title")),
            ).single()
        }

        suspend fun current() = mangas.getMangaById(manga.id)

        suspend fun restore() {
            LibraryOperationCoordinator.shared.exclusive {
                mangas.update(
                    MangaUpdate(
                        id = manga.id,
                        title = "restored title",
                        notes = "restored notes",
                        isPrivate = true,
                        chapterFlags = 73,
                        viewerFlags = 5,
                        memo = JsonObject(mapOf("synthetic" to JsonPrimitive("restored"))),
                    ),
                )
            }
        }

        fun assertNoApplication() {
            assertEquals(0, reads)
            assertEquals(0, applications)
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
        assertEquals(expected, actual.copy(version = expected.version, lastModifiedAt = expected.lastModifiedAt))
    }

    @Test
    fun `remote wait does not hold lease and old nonfavorite response cannot overwrite restore`() = runBlocking {
        database { t ->
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                val generation = coordinator.generation
                started.complete(Unit)
                response.await()
                t.apply(t.remote, generation)
            }
            started.await()
            t.restore()
            val restored = t.current()
            assertFalse(restored.favorite)
            response.complete(Unit)
            assertNull(call.await())
            t.assertNoApplication()
            assertEquals(restored, t.current())
        }
    }

    @Test
    fun `fresh nonfavorite response preserves local fields and applies original metadata semantics`() = runBlocking {
        database { t ->
            t.mangas.update(
                MangaUpdate(t.manga.id, notes = "current", isPrivate = true, chapterFlags = 73, viewerFlags = 5),
            )
            val before = t.current()
            val result = t.apply(t.remote.copy(chapterFlags = 999, viewerFlags = 999), coordinator.generation)!!
            assertContent(
                before.copy(
                    title = t.remote.title,
                    author = t.remote.author,
                    artist = t.remote.artist,
                    description = t.remote.description,
                    genre = t.remote.genre,
                    status = t.remote.status,
                    thumbnailUrl = t.remote.thumbnailUrl,
                    updateStrategy = t.remote.updateStrategy,
                    initialized = true,
                    memo = t.remote.memo,
                ),
                result,
            )
            assertEquals(result, t.current())
            assertEquals(1, t.applications)
        }
    }

    @Test
    fun `response consults current favorite after network wait without generation change`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            val response = CompletableDeferred<Unit>()
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                response.await()
                t.apply(t.remote, generation)
            }
            t.mangas.update(MangaUpdate(t.manga.id, favorite = true, title = "current favorite"))
            val current = t.current()
            response.complete(Unit)
            assertEquals(current, call.await())
            assertEquals(generation, coordinator.generation)
            assertEquals(1, t.reads)
            assertEquals(0, t.applications)
        }
    }

    @Test
    fun `stale absent response is discarded without reads or insertion`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            val remote = t.remote.copy(url = "/absent")
            coordinator.exclusive { }
            assertNull(t.apply(remote, generation))
            t.assertNoApplication()
            assertNull(t.mangas.getMangaByUrlAndSourceId(remote.url, remote.source))
        }
    }

    @Test
    fun `fresh absent response is inserted and returns a persisted manga`() = runBlocking {
        database { t ->
            val remote = t.remote.copy(url = "/absent")
            val result = t.apply(remote, coordinator.generation)!!
            assertTrue(result.id > 0)
            assertEquals(remote.url, result.url)
            assertEquals(remote.source, result.source)
            assertEquals(remote.title, result.title)
            assertEquals(remote.memo, result.memo)
            assertEquals(result, t.mangas.getMangaById(result.id))
        }
    }

    @Test
    fun `batch retains order and current favorites and discards stale batch as a whole`() = runBlocking {
        database { t ->
            t.mangas.update(MangaUpdate(t.manga.id, favorite = true))
            val favorite = t.current()
            val remote = listOf(t.remote.copy(url = "/first absent"), t.remote, t.remote.copy(url = "/second absent"))
            val generation = coordinator.generation
            val result = t.apply(remote, generation)!!
            assertEquals(remote.map { it.url }, result.map { it.url })
            assertEquals(favorite, result[1])
            assertEquals(1, t.applications)
            t.reads = 0
            t.applications = 0
            coordinator.exclusive { }
            assertNull(t.apply(remote, generation))
            t.assertNoApplication()
        }
    }

    @Test
    fun `exclusive waits through local requery and final application`() = runBlocking {
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
            val call = async(start = CoroutineStart.UNDISPATCHED) { t.apply(t.remote, coordinator.generation) }
            reading.await()
            var entered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
            assertFalse(entered)
            finishRead.complete(Unit)
            writing.await()
            assertFalse(entered)
            finishWrite.complete(Unit)
            assertEquals(t.remote.title, call.await()?.title)
            exclusive.join()
            assertTrue(entered)
        }
    }

    @Test
    fun `nested application retains outer lease with exclusive pending`() = runBlocking {
        database { t ->
            val begin = CompletableDeferred<Unit>()
            val applied = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val outer = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.operation {
                    val generation = coordinator.generation
                    begin.await()
                    assertEquals(t.remote.title, t.apply(t.remote, generation)?.title)
                    applied.complete(Unit)
                    release.await()
                }
            }
            var entered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
            begin.complete(Unit)
            applied.await()
            assertFalse(entered)
            release.complete(Unit)
            outer.join()
            exclusive.join()
            assertTrue(entered)
        }
    }

    @Test
    fun `cancellation during remote wait has no local continuation`() = runBlocking {
        database { t ->
            val response = CompletableDeferred<Unit>()
            val call = async(start = CoroutineStart.UNDISPATCHED) {
                val generation = coordinator.generation
                response.await()
                t.apply(t.remote, generation)
            }
            call.cancelAndJoin()
            response.complete(Unit)
            assertTrue(call.isCancelled)
            t.assertNoApplication()
            coordinator.exclusive { }
        }
    }

    @Test
    fun `cancellation at admission or requery cannot write and releases lease`() = runBlocking {
        database { t ->
            val release = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { release.await() } }
            val call = async(start = CoroutineStart.UNDISPATCHED) { t.apply(t.remote, coordinator.generation) }
            call.cancelAndJoin()
            t.assertNoApplication()
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
            val call = async(start = CoroutineStart.UNDISPATCHED) { t.apply(t.remote, coordinator.generation) }
            reading.await()
            call.cancelAndJoin()
            assertEquals(0, t.applications)
            coordinator.exclusive { }
        }
    }

    @Test
    fun `cancelled exclusive drain permits response with unchanged generation`() = runBlocking {
        database { t ->
            val release = CompletableDeferred<Unit>()
            val participant = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.operation { release.await() } }
            val generation = coordinator.generation
            val exclusive =
                launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { error("UNEXPECTED") } }
            val call = async(start = CoroutineStart.UNDISPATCHED) { t.apply(t.remote, generation) }
            exclusive.cancelAndJoin()
            assertEquals(t.remote.title, call.await()?.title)
            assertEquals(generation, coordinator.generation)
            release.complete(Unit)
            participant.join()
        }
    }

    @Test
    fun `each search popular and latest page captures before network and drops old page without retry`() = runBlocking {
        for (kind in listOf("search", "popular", "latest")) {
            database { t ->
                val source = mockk<Source>()
                every { source.id } returns 1L
                val started = CompletableDeferred<Unit>()
                val response = CompletableDeferred<Unit>()
                var requests = 0
                val remotePage: suspend () -> MangasPage = {
                    requests++
                    if (requests == 1) {
                        started.complete(Unit)
                        response.await()
                    }
                    MangasPage(
                        listOf(
                            SManga.create().apply {
                                url = t.remote.url
                                title = "page title"
                            },
                        ),
                        hasNextPage = true,
                    )
                }
                coEvery { source.getSearchManga(any(), any(), any()) } coAnswers { remotePage() }
                coEvery { source.getPopularManga(any()) } coAnswers { remotePage() }
                coEvery { source.getLatestUpdates(any()) } coAnswers { remotePage() }
                val paging = when (kind) {
                    "search" -> SourceSearchPagingSource({ source }, "synthetic", FilterList(), t.apply)
                    "popular" -> SourcePopularPagingSource({ source }, t.apply)
                    else -> SourceLatestPagingSource({ source }, t.apply)
                }
                val load = async(start = CoroutineStart.UNDISPATCHED) {
                    paging.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 1, placeholdersEnabled = false))
                }
                started.await()
                t.restore()
                val restored = t.current()
                response.complete(Unit)
                val dropped = load.await() as PagingSource.LoadResult.Page<Long, Manga>
                assertTrue(dropped.data.isEmpty())
                assertNull(dropped.nextKey)
                assertEquals(1, requests)
                t.assertNoApplication()
                assertEquals(restored, t.current())
                // A new explicit page load gets a new token and stale URLs did not poison seenManga.
                val fresh = paging.load(
                    PagingSource.LoadParams.Append(key = 2L, loadSize = 1, placeholdersEnabled = false),
                ) as PagingSource.LoadResult.Page<Long, Manga>
                assertEquals(listOf("page title"), fresh.data.map { it.title })
                assertEquals(2, requests)
                assertEquals(1, t.applications)
            }
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `deep link captures before resolution and drops stale result without downstream effects`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            database { t ->
                val source = mockk<ResolvableSource>()
                val sources = mockk<SourceManager>()
                every { source.id } returns 1L
                every { source.getUriType(any()) } returns UriType.Chapter
                coEvery { source.getChapter(any()) } returns null
                coEvery { sources.getAll() } returns listOf(source)
                val started = CompletableDeferred<Unit>()
                val response = CompletableDeferred<Unit>()
                coEvery { source.getManga(any()) } coAnswers {
                    started.complete(Unit)
                    response.await()
                    SManga.create().apply {
                        url = t.remote.url
                        title = "old deep link"
                    }
                }
                val model = DeepLinkViewModel("synthetic", sources, t.apply, mockk(), mockk())
                try {
                    started.await()
                    t.restore()
                    val restored = t.current()
                    response.complete(Unit)
                    val result = model.state.first { it != DeepLinkViewModel.State.Loading }
                    assertEquals(DeepLinkViewModel.State.NoResults, result)
                    t.assertNoApplication()
                    coVerify(exactly = 0) { source.getChapter(any()) }
                    assertEquals(restored, t.current())
                } finally {
                    response.complete(Unit)
                    model.viewModelScope.coroutineContext.job.children.toList().forEach { it.cancelAndJoin() }
                    model.viewModelScope.cancel()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
