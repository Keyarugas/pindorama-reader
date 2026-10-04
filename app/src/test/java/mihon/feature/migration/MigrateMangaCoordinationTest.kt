package mihon.feature.migration

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.Source
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mihon.domain.migration.models.MigrationFlag
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.domain.source.models.RemoteMangaUpdate
import mihon.feature.migration.dialog.MigrateDialogViewModel
import mihon.feature.migration.list.models.MigratingManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracks
import java.io.File

/** Real migration use case; repositories and network are simulated here (not a rollback test). */
class MigrateMangaCoordinationTest {
    @Test
    fun `migration also awaits requested download removal before releasing admission`() = runTest {
        val t = Target()
        t.preferences.migrationFlags.set(setOf(MigrationFlag.REMOVE_DOWNLOAD))
        val finishDeletion = CompletableDeferred<Unit>()
        coEvery { t.downloads.deleteMangaAndWait(any(), any(), any()) } coAnswers { finishDeletion.await() }
        val migration =
            launch { assertTrue(t.useCase(t.current, t.target, true, LibraryOperationCoordinator.shared.generation)) }
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
        assertFalse(t.useCase(t.current, t.target, true, LibraryOperationCoordinator.shared.generation))
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
        val migration = launch { t.useCase(t.current, t.target, true, LibraryOperationCoordinator.shared.generation) }
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
        t.useCase(t.current, t.target, replace = true, generation = LibraryOperationCoordinator.shared.generation)
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
        assertFalse(t.useCase(t.current, t.target, true, LibraryOperationCoordinator.shared.generation))
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
        val migration = launch { t.useCase(t.current, t.target, true, LibraryOperationCoordinator.shared.generation) }
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

    @Test
    fun `retained dialog is stale after restore and changes neither flags nor manga`() = runTest {
        val t = Target()
        val cover = mockk<CoverCache>()
        every { cover.getCustomCoverFile(any()) } returns File("/nonexistent/synthetic-cover")
        every { t.downloads.getDownloadCount(any()) } returns 0
        val dialog = MigrateDialogViewModel(t.preferences, cover, t.downloads, t.useCase)
        val generation = LibraryOperationCoordinator.shared.generation
        dialog.init(t.current, t.target, generation)
        dialog.toggleSelection(MigrationFlag.CHAPTER)
        val intent = dialog.state.value
        // The dialog owns no lease while it waits for human confirmation.
        LibraryOperationCoordinator.shared.exclusive { }
        assertEquals(generation, intent.generation)
        // A different dialog/session cannot give an already queued confirmation a new age.
        dialog.init(t.current, t.target, LibraryOperationCoordinator.shared.generation)
        assertFalse(dialog.migrateManga(replace = true, intent = intent))
        assertFalse(dialog.state.value.failed)
        assertEquals(emptySet<MigrationFlag>(), t.preferences.migrationFlags.get())
        coVerify(exactly = 0) { t.getManga.await(any<Long>()) }
        coVerify(exactly = 0) { t.remote.invoke(any<Manga>(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { t.updates.awaitAll(any()) }
    }

    @Test
    fun `migration awaiting admission retains its original generation`() = runTest {
        val t = Target()
        val coordinator = LibraryOperationCoordinator.shared
        val generation = coordinator.generation
        val release = CompletableDeferred<Unit>()
        val exclusive = launch { coordinator.exclusive { release.await() } }
        runCurrent()
        val migration = async { t.useCase(t.current, t.target, true, generation) }
        runCurrent()
        assertFalse(migration.isCompleted)
        coVerify(exactly = 0) { t.getManga.await(any<Long>()) }
        release.complete(Unit)
        exclusive.join()
        assertFalse(migration.await())
        coVerify(exactly = 0) { t.getManga.await(any<Long>()) }
        coVerify(exactly = 0) { t.remote.invoke(any<Manga>(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { t.updates.awaitAll(any()) }
    }

    @Test
    fun `stale batch selection runs neither items nor progress effects`() = runTest {
        val t = Target()
        val generation = LibraryOperationCoordinator.shared.generation
        val selection = listOf(MigrateMangaUseCase.Selection(t.current, t.target))
        LibraryOperationCoordinator.shared.exclusive { }
        var progress = false
        assertFalse(t.useCase.awaitAll(selection, true, generation, emptySet()) { _, _ -> progress = true })
        assertFalse(progress)
        coVerify(exactly = 0) { t.getManga.await(any<Long>()) }
        coVerify(exactly = 0) { t.updates.awaitAll(any()) }
    }

    @Test
    fun `restore cannot enter between items of a manual batch`() = runTest {
        val t = Target()
        val secondCurrent = t.current.copy(id = 3, url = "/second-current")
        val secondTarget = t.target.copy(id = 4, url = "/second-target")
        coEvery { t.getManga.await(secondCurrent.id) } returns secondCurrent
        coEvery { t.getManga.await(secondTarget.id) } returns secondTarget
        val firstApplied = CompletableDeferred<Unit>()
        val continueBatch = CompletableDeferred<Unit>()
        val generation = LibraryOperationCoordinator.shared.generation
        val migration = async {
            t.useCase.awaitAll(
                listOf(
                    MigrateMangaUseCase.Selection(t.current, t.target),
                    MigrateMangaUseCase.Selection(secondCurrent, secondTarget),
                ),
                true,
                generation,
                emptySet(),
            ) { index, completed ->
                assertTrue(completed)
                assertEquals(generation, LibraryOperationCoordinator.shared.generation)
                if (index == 0) {
                    firstApplied.complete(Unit)
                    continueBatch.await()
                }
            }
        }
        firstApplied.await()
        var exclusiveEntered = false
        val exclusive = launch { LibraryOperationCoordinator.shared.exclusive { exclusiveEntered = true } }
        runCurrent()
        assertFalse(exclusiveEntered)
        coVerify(exactly = 1) { t.updates.awaitAll(any()) }
        continueBatch.complete(Unit)
        assertTrue(migration.await())
        exclusive.join()
        coVerify(exactly = 2) { t.updates.awaitAll(any()) }
        assertTrue(exclusiveEntered)
    }

    @Test
    fun `nested migration in actual producer retains lease despite pending exclusive`() = runTest {
        val t = Target()
        val coordinator = LibraryOperationCoordinator.shared
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val model =
            MigratingManga(
                t.current,
                0,
                null,
                "synthetic",
                parent.coroutineContext,
                coordinator,
                StandardTestDispatcher(testScheduler),
            )
        val begin = CompletableDeferred<Unit>()
        val migrated = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val generation = coordinator.generation
        try {
            val producer = model.produce {
                begin.await()
                assertTrue(t.useCase(t.current, t.target, true, generation))
                migrated.complete(Unit)
                release.await()
            }
            runCurrent()
            var exclusiveEntered = false
            val exclusive = launch { coordinator.exclusive { exclusiveEntered = true } }
            runCurrent()
            begin.complete(Unit)
            migrated.await()
            assertFalse(exclusiveEntered)
            release.complete(Unit)
            producer.await()
            exclusive.join()
            assertTrue(exclusiveEntered)
        } finally {
            parent.cancel()
        }
    }
}
