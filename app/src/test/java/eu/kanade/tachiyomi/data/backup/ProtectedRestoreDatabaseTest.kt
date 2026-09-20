package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.PreparedProtectedRestore
import eu.kanade.tachiyomi.data.backup.restore.ProtectedBackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.ProtectedRestoreLoader
import eu.kanade.tachiyomi.data.backup.restore.ProtectedRestorePreferences
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mihon.app.di.AppBindings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.core.common.preference.InMemoryPreferenceStore
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
import java.io.ByteArrayInputStream

/** Real generated schema, queries and repositories. JVM JDBC only, not Android driver validation. */
class ProtectedRestoreDatabaseTest {
    private class Target(val database: Database) {
        val mangaRepository = MangaRepositoryImpl(database)
        val chapters = ChapterRepositoryImpl(database)
        val tracks = TrackRepositoryImpl(database)
        val categories = GetCategories(CategoryRepositoryImpl(database))
        val getChapters = GetChaptersByMangaId(chapters)
        val fetch = FetchInterval(getChapters)
        val store = InMemoryPreferenceStore()
        fun manga(chapterRepository: ChapterRepository = chapters) = MangaRestorer(
            database, categories, GetMangaByUrlAndSourceId(mangaRepository), getChapters,
            UpdateManga(mangaRepository, fetch), GetTracks(tracks), InsertTrack(tracks), fetch,
            chapterRepository, tracks,
        )
        fun restorer() = ProtectedBackupRestorer(
            CategoriesRestorer(database, categories, LibraryPreferences(store)),
            manga(),
            ProtectedRestorePreferences(store),
        )
    }

    private suspend fun withDatabase(block: suspend (Target) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.create(driver).await()
            block(Target(AppBindings.providesDatabase(driver)))
        }
    }

    @Test
    fun `frozen protected fixture restores into independent real databases`() = runBlocking {
        repeat(2) {
            withDatabase { target ->
                val file = checkNotNull(
                    javaClass.getResourceAsStream(
                        "/backup-protected-create/private-gzip-v1.pindobk",
                    ),
                ).use { it.readBytes() }
                val prepared = ProtectedRestoreLoader.prepare(
                    ByteArrayInputStream(file),
                    "Senha sintética 🔒\u0000 e\u0301".toCharArray(),
                )
                target.restorer().restore(prepared, RestoreOptions())
                val manga = target.mangaRepository.getMangaByUrlAndSourceId("/synthetic", 1)
                assertTrue(checkNotNull(manga).isPrivate)
                assertEquals("Private synthetic title", manga.title)
            }
        }
    }

    @Test
    fun `invalid protected file preserves actual existing database`() = runBlocking {
        withDatabase { target ->
            target.manga().restoreProtected(BackupManga(1, "/existing", isPrivate = true), emptyList())
            val file = checkNotNull(
                javaClass.getResourceAsStream(
                    "/backup-protected-create/private-gzip-v1.pindobk",
                ),
            ).use { it.readBytes() }
            file[file.lastIndex] = (file.last().toInt() xor 1).toByte()
            assertThrows<BackupException> {
                val prepared = ProtectedRestoreLoader.prepare(
                    ByteArrayInputStream(file),
                    "Senha sintética 🔒\u0000 e\u0301".toCharArray(),
                )
                target.restorer().restore(prepared, RestoreOptions())
            }
            assertEquals(1, target.database.mangasQueries.getAllMangaSourceAndUrl().awaitAsList().size)
            assertTrue(checkNotNull(target.mangaRepository.getMangaByUrlAndSourceId("/existing", 1)).isPrivate)
        }
    }

    @Test
    fun `private classification is monotonic for every version and import direction`() = runBlocking {
        withDatabase { target ->
            for (existingPrivate in listOf(false, true)) {
                for (importedPrivate in listOf(false, true)) {
                    for (version in listOf(0L, 10L)) {
                        val url = "/$existingPrivate-$importedPrivate-$version"
                        target.manga().restoreProtected(
                            BackupManga(1, url, isPrivate = existingPrivate, version = 5),
                            emptyList(),
                        )
                        target.manga().restoreProtected(
                            BackupManga(1, url, isPrivate = importedPrivate, version = version),
                            emptyList(),
                        )
                        assertEquals(
                            existingPrivate || importedPrivate,
                            target.mangaRepository.getMangaByUrlAndSourceId(url, 1)?.isPrivate,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `entry failure rolls back its insert while earlier committed work remains`() = runBlocking {
        withDatabase { target ->
            target.manga().restoreProtected(BackupManga(1, "/previous", isPrivate = true), emptyList())
            // Failure after manga insertion, while reading its chapters, exercises the real transaction.
            val faulty = object : ChapterRepository by target.chapters {
                override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
                    throw IllegalStateException("synthetic database failure")
                }
            }
            assertThrows<IllegalStateException> {
                target.manga(faulty).restoreProtected(BackupManga(1, "/failed"), emptyList())
            }
            assertEquals(null, target.mangaRepository.getMangaByUrlAndSourceId("/failed", 1))
            assertTrue(checkNotNull(target.mangaRepository.getMangaByUrlAndSourceId("/previous", 1)).isPrivate)
        }
    }

    @Test
    fun `cancellation rolls back current entry and keeps previous transaction`() = runBlocking {
        withDatabase { target ->
            target.manga().restoreProtected(BackupManga(1, "/previous"), emptyList())
            val cancelled = object : ChapterRepository by target.chapters {
                override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
                    throw CancellationException("synthetic cancellation")
                }
            }
            assertThrows<CancellationException> {
                target.manga(cancelled).restoreProtected(BackupManga(1, "/cancelled", isPrivate = true), emptyList())
            }
            assertEquals(null, target.mangaRepository.getMangaByUrlAndSourceId("/cancelled", 1))
            assertFalse(checkNotNull(target.mangaRepository.getMangaByUrlAndSourceId("/previous", 1)).isPrivate)
        }
    }

    @Test
    fun `synthetic pipeline measures decryption validation and real database application`() = runBlocking {
        for (count in listOf(100, 1000, 5000)) {
            val file = syntheticFile(count)
            withDatabase { target ->
                val runtime = Runtime.getRuntime()
                System.gc() // Test measurement only; production never forces GC.
                val baseline = runtime.totalMemory() - runtime.freeMemory()
                val peak = java.util.concurrent.atomic.AtomicLong(baseline)
                val running = java.util.concurrent.atomic.AtomicBoolean(true)
                val sampler = Thread {
                    while (running.get()) {
                        peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                        Thread.sleep(1)
                    }
                }.apply { start() }
                val start = System.nanoTime()
                try {
                    val prepared = ProtectedRestoreLoader.prepare(ByteArrayInputStream(file), "synthetic".toCharArray())
                    val validated = System.nanoTime()
                    target.restorer().restore(prepared, RestoreOptions())
                    val end = System.nanoTime()
                    println(
                        "SYNTHETIC_RESTORE works=$count chapters=${count * 20} bytes=${file.size} " +
                            "prepareMs=${(validated - start) / 1000000} applyMs=${(end - validated) / 1000000} " +
                            "baseline=$baseline peak=${peak.get()} additional=${peak.get() - baseline}",
                    )
                } finally {
                    running.set(false)
                    sampler.join()
                }
                assertEquals(count, target.database.mangasQueries.getAllMangaSourceAndUrl().awaitAsList().size)
            }
        }
    }

    private suspend fun syntheticFile(count: Int): ByteArray {
        val random = java.util.Random(42)
        val data = Backup(
            (1..count).map { id ->
                BackupManga(
                    1,
                    "/$id",
                    "Synthetic $id",
                    isPrivate = id % 2 == 0,
                    description = ByteArray(512).also(random::nextBytes).toHexString(),
                    chapters = (1..20).map { BackupChapter("/$id/$it", "Synthetic chapter $it") },
                )
            },
        )
        val gzip = eu.kanade.tachiyomi.data.backup.create.ProtectedBackupPayload.encode(
            data,
            kotlinx.serialization.protobuf.ProtoBuf,
        )
        return eu.kanade.tachiyomi.data.backup.crypto.BackupCrypto().encrypt(gzip, "synthetic".toCharArray())
    }

    @Test
    fun `valid data restores chapters and categories through actual queries`() = runBlocking {
        withDatabase { target ->
            val data = Backup(
                listOf(
                    BackupManga(
                        1,
                        "/synthetic",
                        isPrivate = true,
                        chapters = listOf(BackupChapter("/chapter", "Synthetic chapter", read = true)),
                        categories = listOf(0),
                    ),
                ),
            )
            data.backupCategories = listOf(BackupCategory("Synthetic category", 0))
            target.restorer().restore(PreparedProtectedRestore.validated(data), RestoreOptions())
            val manga = checkNotNull(target.mangaRepository.getMangaByUrlAndSourceId("/synthetic", 1))
            assertTrue(target.chapters.getChapterByMangaId(manga.id, false).single().read)
            assertEquals("Synthetic category", target.categories.await(manga.id).single().name)
            assertEquals(1, target.database.mangasQueries.getAllMangaSourceAndUrl().awaitAsList().size)
        }
    }
}
