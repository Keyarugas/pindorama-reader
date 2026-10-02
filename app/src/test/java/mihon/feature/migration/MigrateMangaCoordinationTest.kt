package mihon.feature.migration

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.domain.source.models.RemoteMangaUpdate
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks

/** Real migration use case; repositories and network are simulated here (not a rollback test). */
class MigrateMangaCoordinationTest {
    @Test
    fun `migration also awaits requested download removal before releasing admission`() = runTest {
        val t = Target()
        t.preferences.migrationFlags.set(setOf(MigrationFlag.REMOVE_DOWNLOAD))
        val finishDeletion = CompletableDeferred<Unit>()
        coEvery { t.downloads.deleteMangaAndWait(any(), any(), any()) } coAnswers { finishDeletion.await() }
        val migration = launch { assertTrue(t.useCase(t.current, t.target, true)) }
        runCurrent()
        var exclusiveEntered = false
        val exclusive = launch { LibraryOperationCoordinator.shared.exclusive { exclusiveEntered = true } }
        runCurrent()
        assertFalse(exclusiveEntered)
        finishDeletion.complete(Unit)
        migration.join()
        exclusive.join()
        assertTrue(exclusiveEntered)
        coVerify(exactly = 1) { t.downloads.deleteMangaAndWait(any(), any(), any()) }
    }

    @Test
    fun `remote failure is not migration success and releases admission`() = runTest {
        val t = Target()
        coEvery { t.remote.invoke(any<Manga>(), any(), any(), any(), any()) } returns
            Result.failure(IllegalStateException("synthetic"))
        assertFalse(t.useCase(t.current, t.target, true))
        coVerify(exactly = 0) { t.updates.awaitAll(any()) }
        LibraryOperationCoordinator.shared.exclusive { }
    }

    @Test
    fun `cancel migration during network wait prevents late writes and releases admission`() = runTest {
        val t = Target()
        val response = CompletableDeferred<Unit>()
        coEvery { t.remote.invoke(any<Manga>(), any(), any(), any(), any()) } coAnswers {
            response.await()
            Result.success(RemoteMangaUpdate(t.target, emptyList()))
        }
        val migration = launch { t.useCase(t.current, t.target, true) }
        runCurrent()
        migration.cancelAndJoin()
        response.complete(Unit)
        runCurrent()
        coVerify(exactly = 0) { t.updates.awaitAll(any()) }
        LibraryOperationCoordinator.shared.exclusive { }
    }

    private class Target {
        val preferences = SourcePreferences(InMemoryPreferenceStore()).apply { migrationFlags.set(emptySet()) }
        val getManga = mockk<GetManga>()
        val updates = mockk<UpdateManga>()
        val downloads = mockk<DownloadManager>()
        val remote = mockk<UpdateMangaFromRemote>()
        val tracks = mockk<GetTracks>()
        val trackers = mockk<TrackerManager>()
        val sources = mockk<SourceManager>()
        val source = mockk<Source>()
        val current = Manga.create().copy(id = 1, source = 1, url = "/current", favorite = true)
        val target = Manga.create().copy(id = 2, source = 1, url = "/target")
        init {
            coEvery { sources.get(any()) } returns source
            every { trackers.trackers } returns emptyList()
            coEvery { getManga.await(current.id) } returns current.copy(isPrivate = true, viewerFlags = 123)
            coEvery { getManga.await(target.id) } returns target
            coEvery { tracks.await(any()) } returns emptyList()
            coEvery { updates.awaitAll(any()) } returns true
            coEvery { remote.invoke(any<Manga>(), any(), any(), any(), any()) } returns
                Result.success(RemoteMangaUpdate(target, emptyList()))
        }
        val useCase = MigrateMangaUseCase(
            preferences, getManga, trackers, sources, downloads, updates, mockk(), mockk(),
            mockk(), mockk(), tracks, mockk(), mockk(), remote,
        )
    }

    @Test
    fun `migration revalidates retained works and uses current privacy and flags`() = runTest {
        val t = Target()
        t.useCase(t.current, t.target, replace = true)
        coVerify {
            t.updates.awaitAll(
                match { updates ->
                    updates.any { it.id == t.target.id && it.isPrivate == true && it.viewerFlags == 123L }
                },
            )
        }
    }

    @Test
    fun `changed identity refuses migration before remote request or local writes`() = runTest {
        val t = Target()
        coEvery { t.getManga.await(t.target.id) } returns t.target.copy(url = "/different")
        assertFalse(t.useCase(t.current, t.target, true))
        coVerify(exactly = 0) { t.remote.invoke(any<Manga>(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { t.updates.awaitAll(any()) }
    }

    @Test
    fun `actual migration retains admission across remote response and final write`() = runTest {
        val t = Target()
        val response = CompletableDeferred<Unit>()
        coEvery { t.remote.invoke(any<Manga>(), any(), any(), any(), any()) } coAnswers {
            response.await()
            Result.success(RemoteMangaUpdate(t.target, emptyList()))
        }
        val migration = launch { t.useCase(t.current, t.target, true) }
        runCurrent()
        var exclusiveEntered = false
        val exclusive = launch { LibraryOperationCoordinator.shared.exclusive { exclusiveEntered = true } }
        runCurrent()
        assertFalse(exclusiveEntered)
        response.complete(Unit)
        migration.join()
        exclusive.join()
        assertTrue(exclusiveEntered)
        coVerify(exactly = 1) { t.updates.awaitAll(any()) }
    }
}
