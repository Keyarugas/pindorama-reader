package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
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
    private class Target(val database: Database, val driver: JdbcSqliteDriver) {
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
            database,
        )
    }

    private suspend fun withDatabase(block: suspend (Target) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            Database.Schema.create(driver).await()
            block(Target(AppBindings.providesDatabase(driver), driver))
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
    fun `whole library transaction rolls back categories and all works after chapter SQL failure`() = runBlocking {
        for (completed in listOf(1, 3, 10)) {
            withDatabase { target ->
                val categoriesBefore = target.categories.await()
                target.manga().restoreProtected(
                    BackupManga(
                        1,
                        "/existing",
                        isPrivate = completed != 10,
                        chapters = listOf(BackupChapter("/existing/ch", "Synthetic", read = false)),
                    ),
                    emptyList(),
                )
                val before = target.mangaRepository.getMangaByUrlAndSourceId("/existing", 1)
                val chaptersBefore = target.chapters.getChapterByMangaId(checkNotNull(before).id, false)
                val historyBefore = target.database.historyQueries
                    .getHistoryByChapterUrlAndMangaId("/existing/ch", before.id).awaitAsOneOrNull()
                target.driver.execute(
                    null,
                    "CREATE TRIGGER fail_chapter BEFORE INSERT ON chapters WHEN NEW.url = '/bad' " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic'); END",
                    0,
                ).await()
                val imported = listOf(
                    BackupManga(
                        1,
                        "/existing",
                        "changed",
                        version = 10,
                        isPrivate = true,
                        chapters = listOf(BackupChapter("/existing/ch", "Synthetic", read = true)),
                        history = listOf(BackupHistory("/existing/ch", 1000, 10)),
                        categories = listOf(0),
                    ),
                ) +
                    (1..completed).map { BackupManga(1, "/new$it", isPrivate = true) } +
                    BackupManga(1, "/failed", chapters = listOf(BackupChapter("/bad", "synthetic")))
                val data = Backup(imported).apply { backupCategories = listOf(BackupCategory("Synthetic")) }
                assertThrows<BackupException> {
                    target.restorer().restore(PreparedProtectedRestore.validated(data), RestoreOptions())
                }
                assertEquals(before, target.mangaRepository.getMangaByUrlAndSourceId("/existing", 1))
                assertEquals(1, target.database.mangasQueries.getAllMangaSourceAndUrl().awaitAsList().size)
                assertEquals(categoriesBefore, target.categories.await())
                assertEquals(chaptersBefore, target.chapters.getChapterByMangaId(before.id, false))
                assertEquals(
                    historyBefore,
                    target.database.historyQueries
                        .getHistoryByChapterUrlAndMangaId("/existing/ch", before.id).awaitAsOneOrNull(),
                )
            }
        }
    }

    @Test
    fun `category SQL failure rolls back earlier categories without reaching works`() = runBlocking {
        withDatabase { target ->
            val categoriesBefore = target.categories.await()
            target.driver.execute(
                null,
                "CREATE TRIGGER fail_category BEFORE INSERT ON categories WHEN NEW.name = 'Broken' " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic'); END",
                0,
            ).await()
            val data = Backup(listOf(BackupManga(1, "/new"))).apply {
                backupCategories = listOf(BackupCategory("First", 0), BackupCategory("Broken", 1))
            }
            assertThrows<BackupException> {
                target.restorer().restore(PreparedProtectedRestore.validated(data), RestoreOptions())
            }
            assertEquals(categoriesBefore, target.categories.await())
            assertTrue(target.database.mangasQueries.getAllMangaSourceAndUrl().awaitAsList().isEmpty())
        }
    }

    @Test
    fun `cancel outer transaction after nested commits rolls back all imported data`() = runBlocking {
        withDatabase { target ->
            val categoriesBefore = target.categories.await()
            target.manga().restoreProtected(BackupManga(1, "/existing", isPrivate = true), emptyList())
            val failingChapters = object : ChapterRepository by target.chapters {
                var calls = 0
                override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> {
                    if (++calls == 4) throw CancellationException("synthetic")
                    return target.chapters.getChapterByMangaId(mangaId, applyScanlatorFilter)
                }
            }
            val restorer = ProtectedBackupRestorer(
                CategoriesRestorer(target.database, target.categories, LibraryPreferences(target.store)),
                target.manga(failingChapters),
                ProtectedRestorePreferences(target.store),
                target.database,
            )
            val data = Backup((1..5).map { BackupManga(1, "/$it", isPrivate = true) }).apply {
                backupCategories = listOf(BackupCategory("Synthetic"))
            }
            assertThrows<CancellationException> {
                restorer.restore(PreparedProtectedRestore.validated(data), RestoreOptions())
            }
            assertEquals(categoriesBefore, target.categories.await())
            assertEquals(1, target.database.mangasQueries.getAllMangaSourceAndUrl().awaitAsList().size)
            assertTrue(checkNotNull(target.mangaRepository.getMangaByUrlAndSourceId("/existing", 1)).isPrivate)
        }
    }

    @Test
    fun `abrupt child process exit leaves uncommitted database changes absent after reopen`(
        @org.junit.jupiter.api.io.TempDir directory: java.nio.file.Path,
    ) = runBlocking {
        val path = directory.resolve("synthetic.db").toString()
        val namesBefore = JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            Database.Schema.create(driver).await()
            val database = AppBindings.providesDatabase(driver)
            database.categoriesQueries.insert("Existing", 0, 0)
            GetCategories(CategoryRepositoryImpl(database)).await().map { it.name }
        }
        val urls = generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<java.net.URLClassLoader>().flatMap { it.urLs.asSequence() }
            .map { java.io.File(it.toURI()).path }.toList()
        val classpath = (urls + System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .distinct().joinToString(java.io.File.pathSeparator)
        val output = directory.resolve("child.log").toFile()
        val child = ProcessBuilder(
            java.io.File(System.getProperty("java.home"), "bin/java").path,
            "-cp",
            classpath,
            "eu.kanade.tachiyomi.data.backup.RecoveryCrashProbe",
            path,
        ).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(23, child.exitValue(), output.readText())
        } finally {
            if (child.isAlive) child.destroyForcibly().waitFor()
        }
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            val categories = GetCategories(CategoryRepositoryImpl(AppBindings.providesDatabase(driver))).await()
            assertEquals(namesBefore, categories.map { it.name })
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

/** Owned synthetic subprocess only: halt skips finally/close to exercise SQLite recovery on reopen. */
object RecoveryCrashProbe {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${args.single()}")
        val database = AppBindings.providesDatabase(driver)
        database.transaction {
            database.transaction { database.categoriesQueries.insert("Uncommitted", 1, 0) }
            Runtime.getRuntime().halt(23)
        }
        driver.close()
    }
}
