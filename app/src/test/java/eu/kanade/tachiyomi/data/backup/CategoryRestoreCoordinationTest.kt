package eu.kanade.tachiyomi.data.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.AddMangaToLibrary
import eu.kanade.domain.manga.interactor.RemoveMangaFromLibrary
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.source.Source
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.plus
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

/** Real SQLite categories/relations, synthetic remote effects and deterministic suspension points. */
class CategoryRestoreCoordinationTest {
    private val coordinator = LibraryOperationCoordinator.shared

    private class Driver(private val delegate: SqlDriver) : SqlDriver by delegate {
        var onSql: suspend (String) -> Unit = {}

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> = QueryResult.AsyncValue {
            onSql(sql)
            delegate.execute(identifier, sql, parameters, binders).await()
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> = QueryResult.AsyncValue {
            onSql(sql)
            delegate.executeQuery(identifier, sql, mapper, parameters, binders).await()
        }
    }

    private class Target(val database: Database, val driver: Driver) {
        val mangas = MangaRepositoryImpl(database)
        val categories = CategoryRepositoryImpl(database)
        val preferences = LibraryPreferences(InMemoryPreferenceStore())
        val downloads = DownloadPreferences(InMemoryPreferenceStore())
        val covers = mockk<CoverCache>()
        var coverDeletes = 0
        val tracks = mockk<AddTracks>()
        val sources = mockk<SourceManager>()
        var reads = 0
        var tracking = 0
        var relations = 0
        var categoryWrites = 0
        val updates = mutableListOf<MangaUpdate>()
        var onRead: suspend () -> Unit = {}
        var onRelationRead: suspend () -> Unit = {}
        var onRelationWrite: suspend () -> Unit = {}
        var onUpdate: suspend () -> Unit = {}
        var onCategoryWrite: suspend () -> Unit = {}
        var onTracking: suspend () -> Unit = {}
        val repository = object : MangaRepository by mangas {
            override suspend fun getMangaByUrlAndSourceId(url: String, sourceId: Long): Manga? {
                reads++
                onRead()
                return mangas.getMangaByUrlAndSourceId(url, sourceId)
            }
            override suspend fun update(update: MangaUpdate): Boolean {
                onUpdate()
                updates.add(update)
                return mangas.update(update)
            }
            override suspend fun setMangaCategories(mangaId: Long, categoryIds: List<Long>) {
                onRelationWrite()
                relations++
                mangas.setMangaCategories(mangaId, categoryIds)
            }
        }
        val categoryRepository = object : CategoryRepository by categories {
            override suspend fun get(id: Long): Category? {
                reads++
                onRead()
                return categories.get(id)
            }
            override suspend fun getAll(): List<Category> {
                reads++
                onRead()
                return categories.getAll()
            }
            override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
                reads++
                onRelationRead()
                return categories.getCategoriesByMangaId(mangaId)
            }
            override suspend fun insert(category: Category) {
                onCategoryWrite()
                categoryWrites++
                categories.insert(category)
            }
            override suspend fun updateName(categoryId: Long, name: String) {
                onCategoryWrite()
                categoryWrites++
                categories.updateName(categoryId, name)
            }
            override suspend fun delete(categoryId: Long) {
                onCategoryWrite()
                categoryWrites++
                categories.delete(categoryId)
            }
            override suspend fun updateFlags(categoryId: Long, flags: Long) {
                onCategoryWrite()
                categoryWrites++
                categories.updateFlags(categoryId, flags)
            }
            override suspend fun updateFlagsBatch(flagsByCategory: Map<Long, Long>) {
                onCategoryWrite()
                categoryWrites++
                categories.updateFlagsBatch(flagsByCategory)
            }
            override suspend fun updateAllFlags(flags: Long?) {
                onCategoryWrite()
                categoryWrites++
                categories.updateAllFlags(flags)
            }
            override suspend fun updateAllOrders(orderedIds: List<Long>) {
                onCategoryWrite()
                categoryWrites++
                categories.updateAllOrders(orderedIds)
            }
        }
        val membership = SetMangaCategories(repository, categoryRepository)
        val defaults = SetMangaDefaultChapterFlags(
            preferences,
            SetMangaChapterFlags(repository),
            GetFavorites(repository),
            repository,
        )
        val add = AddMangaToLibrary(repository, membership, defaults, tracks, sources)
        val remove = RemoveMangaFromLibrary(repository, covers)
        val create = CreateCategoryWithName(categoryRepository, preferences)
        val delete = DeleteCategory(categoryRepository, preferences, downloads)
        val rename = RenameCategory(categoryRepository)
        val reorder = ReorderCategory(categoryRepository)
        val sort = SetSortModeForCategory(preferences, categoryRepository)
        val reset = ResetCategoryFlags(preferences, categoryRepository)
        lateinit var manga: Manga
        lateinit var second: Manga
        lateinit var cats: List<Category>

        init {
            every { covers.deleteFromCache(any(), true) } answers {
                coverDeletes++
                0
            }
            coEvery { sources.getOrStub(any()) } returns mockk<Source>()
            coEvery { tracks.bindEnhancedTrackers(any(), any()) } coAnswers {
                LibraryOperationCoordinator.shared.operation {
                    tracking++
                    onTracking()
                }
            }
        }

        suspend fun seed() {
            val entries = mangas.insertNetworkManga(
                listOf(
                    Manga.create().copy(source = 1, url = "/first", title = "first"),
                    Manga.create().copy(source = 1, url = "/second", title = "second", favorite = true),
                ),
            )
            manga = entries[0]
            second = entries[1]
            for (i in 0..2) categories.insert(Category(0, "category $i", i.toLong(), 0))
            cats = categories.getAll().filterNot { it.isSystemCategory }
            mangas.setMangaCategories(manga.id, listOf(cats[0].id))
            mangas.setMangaCategories(second.id, listOf(cats[1].id))
        }

        suspend fun ids(manga: Manga) = categories.getCategoriesByMangaId(manga.id).map { it.id }
    }

    private suspend fun database(block: suspend (Target) -> Unit) {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { jdbc ->
            jdbc.execute(null, "PRAGMA foreign_keys = ON", 0).await()
            Database.Schema.create(jdbc).await()
            val driver = Driver(jdbc)
            val target = Target(AppBindings.providesDatabase(driver), driver)
            target.seed()
            block(target)
        }
        // Also detects leases accidentally left behind after cancellation/failure.
        coordinator.exclusive { }
    }

    private suspend fun exclusiveWaits(
        install: (suspend () -> Unit) -> Unit,
        operation: suspend () -> Unit,
    ) = coroutineScope {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        install {
            reached.complete(Unit)
            release.await()
        }
        val call = launch(start = CoroutineStart.UNDISPATCHED) { operation() }
        reached.await()
        var entered = false
        val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
        assertFalse(entered)
        release.complete(Unit)
        call.join()
        exclusive.join()
        assertTrue(entered)
    }

    @Test
    fun `stale selection and explicit add have no reads writes defaults or tracking`() = runBlocking {
        database { t ->
            val selection = t.membership.loadSelection(listOf(t.manga), coordinator.generation)!!
            coordinator.exclusive {
                t.mangas.update(MangaUpdate(t.manga.id, notes = "restored", chapterFlags = 256))
                t.mangas.setMangaCategories(t.manga.id, listOf(t.cats[2].id))
            }
            val reads = t.reads
            assertFalse(t.membership.await(t.manga, listOf(t.cats[0].id), selection.generation))
            assertFalse(t.add.await(t.manga, listOf(t.cats[0].id), selection.generation, applyDefaults = true))
            t.membership.awaitAll(listOf(t.manga, t.second), listOf(t.cats[0].id), emptyList(), selection.generation)
            assertNull(t.membership.loadSelection(listOf(t.manga), selection.generation))
            assertEquals(reads, t.reads)
            assertEquals(0, t.relations)
            assertEquals(0, t.tracking)
            assertTrue(t.updates.isEmpty())
            assertEquals(listOf(t.cats[2].id), t.ids(t.manga))
            assertEquals("restored", t.mangas.getMangaById(t.manga.id)!!.notes)
        }
    }

    @Test
    fun `current selection only replaces relations and preserves unrelated manga state`() = runBlocking {
        database { t ->
            t.mangas.update(
                MangaUpdate(t.manga.id, isPrivate = true, notes = "current", chapterFlags = 123, viewerFlags = 456),
            )
            val before = t.mangas.getMangaById(t.manga.id)!!
            assertTrue(t.membership.await(t.manga, listOf(t.cats[1].id, t.cats[2].id), coordinator.generation))
            val after = t.mangas.getMangaById(t.manga.id)!!
            assertEquals(before, after.copy(version = before.version, lastModifiedAt = before.lastModifiedAt))
            assertEquals(listOf(t.cats[1].id, t.cats[2].id), t.ids(t.manga))
        }
    }

    @Test
    fun `explicit add for browse manga and history uses narrow membership updates`() = runBlocking {
        for (applyDefaults in listOf(true, false)) {
            database { t ->
                t.mangas.update(
                    MangaUpdate(
                        t.manga.id,
                        isPrivate = true,
                        notes = "current",
                        viewerFlags = 789,
                        title = "current title",
                    ),
                )
                val before = t.mangas.getMangaById(t.manga.id)!!
                assertTrue(t.add.await(t.manga, listOf(t.cats[2].id), coordinator.generation, applyDefaults))
                val after = t.mangas.getMangaById(t.manga.id)!!
                assertTrue(after.favorite)
                assertTrue(after.dateAdded > 0)
                assertEquals(before.isPrivate, after.isPrivate)
                assertEquals(before.notes, after.notes)
                assertEquals(before.viewerFlags, after.viewerFlags)
                assertEquals(before.title, after.title)
                if (!applyDefaults) assertEquals(before.chapterFlags, after.chapterFlags)
                val update = t.updates.last()
                assertEquals(MangaUpdate(t.manga.id, favorite = true, dateAdded = after.dateAdded), update)
                assertEquals(listOf(t.cats[2].id), t.ids(t.manga))
                assertEquals(1, t.tracking)
            }
        }
    }

    @Test
    fun `explicit add never toggles an already favorite manga or reapplies defaults tracking`() = runBlocking {
        database { t ->
            t.mangas.update(MangaUpdate(t.manga.id, favorite = true, dateAdded = 42, chapterFlags = 99))
            assertTrue(t.add.await(t.manga, listOf(t.cats[1].id), coordinator.generation, applyDefaults = true))
            val current = t.mangas.getMangaById(t.manga.id)!!
            assertTrue(current.favorite)
            assertEquals(42, current.dateAdded)
            assertEquals(99, current.chapterFlags)
            assertTrue(t.updates.isEmpty())
            assertEquals(0, t.tracking)
        }
    }

    @Test
    fun `identity mismatch and missing categories reject before derived effects`() = runBlocking {
        database { t ->
            for (manga in listOf(t.manga.copy(id = 999), t.manga.copy(source = 999), t.manga.copy(url = "/absent"))) {
                assertFalse(t.membership.await(manga, listOf(t.cats[1].id), coordinator.generation))
                assertFalse(t.add.await(manga, listOf(t.cats[1].id), coordinator.generation, applyDefaults = true))
                assertNull(t.remove.await(manga, coordinator.generation))
            }
            assertFalse(t.add.await(t.manga, listOf(999), coordinator.generation, applyDefaults = true))
            assertEquals(0, t.relations)
            assertEquals(0, t.tracking)
            assertTrue(t.updates.isEmpty())
        }
    }

    @Test
    fun `composed add retains lease through defaults tracking membership and relations`() = runBlocking {
        for (phase in 0..3) {
            database { t ->
                var updates = 0
                exclusiveWaits(
                    install = { barrier ->
                        when (phase) {
                            0 -> t.onUpdate = { if (++updates == 1) barrier() }
                            1 -> t.onTracking = barrier
                            2 -> t.onUpdate = { if (++updates == 2) barrier() }
                            3 -> t.onRelationWrite = barrier
                        }
                    },
                    operation = {
                        t.add.await(t.manga, listOf(t.cats[2].id), coordinator.generation, applyDefaults = true)
                    },
                )
                assertTrue(t.mangas.getMangaById(t.manga.id)!!.favorite)
                assertEquals(listOf(t.cats[2].id), t.ids(t.manga))
            }
        }
    }

    @Test
    fun `library category batch keeps one lease through relation read first and last write`() = runBlocking {
        for (phase in 0..2) {
            database { t ->
                var writes = 0
                exclusiveWaits(
                    install = { barrier ->
                        when (phase) {
                            0 -> t.onRelationRead = barrier
                            1 -> t.onRelationWrite = { if (++writes == 1) barrier() }
                            2 -> t.onRelationWrite = { if (++writes == 2) barrier() }
                        }
                    },
                    operation = {
                        t.membership.awaitAll(
                            listOf(t.manga, t.second),
                            listOf(t.cats[2].id),
                            emptyList(),
                            coordinator.generation,
                        )
                    },
                )
                assertEquals(listOf(t.cats[0].id, t.cats[2].id), t.ids(t.manga))
                assertEquals(listOf(t.cats[1].id, t.cats[2].id), t.ids(t.second))
                assertEquals(2, t.relations)
            }
        }
    }

    @Test
    fun `stale delete rename reorder sort and reset perform no reads writes or preference cleanup`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            t.preferences.defaultCategory.set(t.cats[0].id.toInt())
            t.preferences.updateCategories.set(setOf(t.cats[0].id.toString()))
            coordinator.exclusive { t.categories.updateFlags(t.cats[0].id, 512) }
            assertEquals(DeleteCategory.Result.Unchanged, t.delete.await(t.cats[0].id, generation))
            assertEquals(RenameCategory.Result.Unchanged, t.rename.await(t.cats[0], "renamed", generation))
            assertEquals(ReorderCategory.Result.Unchanged, t.reorder.await(t.cats[0], 2, generation))
            t.sort.await(t.cats[0], LibrarySort.Type.Random, LibrarySort.Direction.Descending, generation)
            assertFalse(t.reset.await(generation))
            assertEquals(0, t.reads)
            assertEquals(0, t.categoryWrites)
            assertEquals(t.cats[0].id.toInt(), t.preferences.defaultCategory.get())
            assertEquals(setOf(t.cats[0].id.toString()), t.preferences.updateCategories.get())
            assertEquals(512, t.categories.get(t.cats[0].id)!!.flags)
        }
    }

    @Test
    fun `delete keeps cascade reorder and related preference cleanup within the lease`() = runBlocking {
        database { t ->
            val id = t.cats[0].id
            t.preferences.defaultCategory.set(id.toInt())
            t.preferences.updateCategories.set(setOf(id.toString(), t.cats[1].id.toString()))
            t.downloads.removeExcludeCategories.set(setOf(id.toString()))
            var observedCleanup = false
            t.driver.onSql = { sql ->
                if (sql.startsWith("UPDATE categories") && "sort" in sql) {
                    assertEquals(t.preferences.defaultCategory.defaultValue(), t.preferences.defaultCategory.get())
                    assertFalse(id.toString() in t.preferences.updateCategories.get())
                    assertTrue(t.downloads.removeExcludeCategories.get().isEmpty())
                    observedCleanup = true
                }
            }
            var writes = 0
            exclusiveWaits(
                install = { barrier -> t.onCategoryWrite = { if (++writes == 2) barrier() } },
                operation = { assertEquals(DeleteCategory.Result.Success, t.delete.await(id)) },
            )
            assertNull(t.categories.get(id))
            assertTrue(t.ids(t.manga).isEmpty())
            assertEquals(listOf(0L, 1L, 2L), t.categories.getAll().map { it.order })
            assertTrue(observedCleanup)
        }
    }

    @Test
    fun `reorder uses persisted order and retains lease through every SQL update`() = runBlocking {
        database { t ->
            t.categories.updateAllOrders(listOf(t.cats[2].id, t.cats[0].id, t.cats[1].id))
            var writes = 0
            exclusiveWaits(
                install = { barrier ->
                    t.driver.onSql = { sql ->
                        if (sql.startsWith("UPDATE categories") && "sort" in sql && ++writes == 2) barrier()
                    }
                },
                operation = { assertEquals(ReorderCategory.Result.Success, t.reorder.await(t.cats[0], 2)) },
            )
            assertEquals(
                listOf(t.cats[2].id, t.cats[1].id, t.cats[0].id),
                t.categories.getAll().filterNot {
                    it.isSystemCategory
                }.map { it.id },
            )
        }
    }

    @Test
    fun `category sort uses current flags preserving unrelated bits locally and globally`() = runBlocking {
        database { t ->
            t.preferences.categorizedDisplaySettings.set(true)
            t.categories.updateFlags(t.cats[0].id, 512L + LibrarySort.Type.LastRead + LibrarySort.Direction.Ascending)
            t.sort.await(t.cats[0], LibrarySort.Type.TotalChapters, LibrarySort.Direction.Descending)
            assertEquals(
                512L + LibrarySort.Type.TotalChapters + LibrarySort.Direction.Descending,
                t.categories.get(t.cats[0].id)!!.flags,
            )
            t.categories.updateFlags(t.cats[1].id, 1024)
            t.preferences.categorizedDisplaySettings.set(false)
            t.sort.await(t.cats[0], LibrarySort.Type.DateAdded, LibrarySort.Direction.Ascending)
            assertEquals(
                512L + LibrarySort.Type.DateAdded + LibrarySort.Direction.Ascending,
                t.categories.get(t.cats[0].id)!!.flags,
            )
            assertEquals(
                1024L + LibrarySort.Type.DateAdded + LibrarySort.Direction.Ascending,
                t.categories.get(t.cats[1].id)!!.flags,
            )
        }
    }

    @Test
    fun `global category sort and reset keep one lease`() = runBlocking {
        for (reset in listOf(false, true)) {
            database { t ->
                var updates = 0
                exclusiveWaits(
                    install = { barrier ->
                        if (reset) {
                            t.onCategoryWrite = barrier
                        } else {
                            t.driver.onSql = { sql ->
                                if (sql.startsWith("UPDATE categories") && "flags" in sql && ++updates == 2) {
                                    barrier()
                                }
                            }
                        }
                    },
                    operation = {
                        if (reset) {
                            assertTrue(t.reset.await())
                        } else {
                            t.sort.await(null as Category?, LibrarySort.Type.LastRead, LibrarySort.Direction.Descending)
                        }
                    },
                )
                val expected = if (reset) t.preferences.sortingMode.get().flag else LibrarySort.Type.LastRead.flag
                assertTrue(t.categories.getAll().all { it.flags == expected })
            }
        }
    }

    @Test
    fun `global sort rolls back every category when its second flags update fails`() = runBlocking {
        database { t ->
            t.categories.getAll().forEachIndexed { index, category ->
                t.categories.updateFlags(category.id, (index + 1) * 512L + LibrarySort.Type.LastRead)
            }
            val before = t.categories.getAll()
            assertTrue(before.size >= 3)
            assertEquals(before.size, before.map { it.flags }.distinct().size)
            var updates = 0
            t.driver.onSql = { sql ->
                if (sql.startsWith("UPDATE categories") && "flags" in sql && ++updates == 2) {
                    error("SYNTHETIC_FLAGS_WRITE_FAILURE")
                }
            }
            val failure = assertThrows<IllegalStateException> {
                t.sort.await(null as Category?, LibrarySort.Type.TotalChapters, LibrarySort.Direction.Descending)
            }
            assertEquals("SYNTHETIC_FLAGS_WRITE_FAILURE", failure.message)
            assertEquals(2, updates)
            t.driver.onSql = {}
            assertEquals(before, t.categories.getAll())
        }
    }

    @Test
    fun `create uses current nextOrder validates duplicate and keeps read insert interval admitted`() = runBlocking {
        database { t ->
            t.categories.updateAllOrders(listOf(t.cats[2].id, t.cats[0].id, t.cats[1].id))
            exclusiveWaits(
                install = { barrier -> t.onCategoryWrite = barrier },
                operation = { assertEquals(CreateCategoryWithName.Result.Success, t.create.await("fresh")) },
            )
            val created = t.categories.getAll().last()
            assertEquals("fresh", created.name)
            assertEquals(3, created.order)
            t.onCategoryWrite = {}
            assertEquals(CreateCategoryWithName.Result.Unchanged, t.create.await("fresh"))
            assertEquals(1, t.categoryWrites)
        }
    }

    @Test
    fun `rename updates only name and absent targets have no effects`() = runBlocking {
        database { t ->
            t.categories.updateFlags(t.cats[0].id, 1024)
            assertEquals(RenameCategory.Result.Success, t.rename.await(t.cats[0], "fresh"))
            assertEquals(t.cats[0].copy(name = "fresh", flags = 1024), t.categories.get(t.cats[0].id))
            assertEquals(RenameCategory.Result.Unchanged, t.rename.await(999, "absent"))
            assertEquals(DeleteCategory.Result.Unchanged, t.delete.await(999))
            assertEquals(1, t.categoryWrites)
        }
    }

    @Test
    fun `cancelled exclusive before application keeps a waiting selection valid`() = runBlocking {
        database { t ->
            val release = CompletableDeferred<Unit>()
            val holder = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.operation { release.await() } }
            val generation = coordinator.generation
            val exclusive =
                launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { error("must not apply") } }
            val call =
                async(start = CoroutineStart.UNDISPATCHED) {
                    t.membership.await(t.manga, listOf(t.cats[2].id), generation)
                }
            assertEquals(0, t.reads)
            exclusive.cancelAndJoin()
            assertTrue(call.await())
            assertEquals(generation, coordinator.generation)
            release.complete(Unit)
            holder.join()
        }
    }

    @Test
    fun `nested membership add and reorder do not release the outer lease with exclusive pending`() = runBlocking {
        database { t ->
            val entered = CompletableDeferred<Unit>()
            val invoke = CompletableDeferred<Unit>()
            val innerDone = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.operation {
                    entered.complete(Unit)
                    invoke.await()
                    val generation = coordinator.generation
                    assertTrue(t.add.await(t.manga, listOf(t.cats[2].id), generation, applyDefaults = true))
                    assertEquals(ReorderCategory.Result.Success, t.reorder.await(t.cats[0], 2, generation))
                    assertTrue(t.remove.await(t.manga, generation) != null)
                    innerDone.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            var exclusiveEntered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive {
                    exclusiveEntered =
                        true
                }
            }
            invoke.complete(Unit)
            innerDone.await()
            assertFalse(exclusiveEntered)
            release.complete(Unit)
            holder.join()
            exclusive.join()
            assertTrue(exclusiveEntered)
        }
    }

    @Test
    fun `cancel admission requery and prewrite leaves no late membership or derived effects`() = runBlocking {
        for (phase in 0..2) {
            database { t ->
                val reached = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val exclusive = if (phase == 0) {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        coordinator.exclusive {
                            reached.complete(Unit)
                            release.await()
                        }
                    }
                } else {
                    null
                }
                if (phase == 1) {
                    t.onRead = {
                        reached.complete(Unit)
                        release.await()
                    }
                }
                if (phase == 2) {
                    t.onRelationWrite = {
                        reached.complete(Unit)
                        release.await()
                    }
                }
                val generation = coordinator.generation
                val call =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        t.membership.await(t.manga, listOf(t.cats[2].id), generation)
                    }
                reached.await()
                call.cancelAndJoin()
                release.complete(Unit)
                exclusive?.join()
                assertEquals(0, t.relations)
                assertEquals(listOf(t.cats[0].id), t.ids(t.manga))
                assertTrue(t.updates.isEmpty())
                assertEquals(0, t.tracking)
            }
        }
    }

    @Test
    fun `noncancellable category intent remains admitted until its actual maintenance finishes`() = runBlocking {
        database { t ->
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            t.onCategoryWrite = {
                reached.complete(Unit)
                release.await()
            }
            val call = launch(start = CoroutineStart.UNDISPATCHED) { t.rename.await(t.cats[0], "completed") }
            reached.await()
            call.cancel()
            var entered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
            assertFalse(entered)
            release.complete(Unit)
            call.join()
            exclusive.join()
            assertEquals("completed", t.categories.get(t.cats[0].id)!!.name)
            assertTrue(entered)
        }
    }

    @Test
    fun `selection awaiting restore is discarded without minting a fresh generation`() = runBlocking {
        database { t ->
            val release = CompletableDeferred<Unit>()
            val generation = coordinator.generation
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { release.await() } }
            val load = async(start = CoroutineStart.UNDISPATCHED) {
                t.membership.loadSelection(listOf(t.manga), generation)
            }
            assertFalse(load.isCompleted)
            assertEquals(0, t.reads)
            release.complete(Unit)
            exclusive.join()
            assertNull(load.await())
            assertEquals(0, t.reads)
            assertEquals(0, t.relations)
        }
    }

    @Test
    fun `create name command waiting for restore computes order from restored categories`() = runBlocking {
        database { t ->
            val release = CompletableDeferred<Unit>()
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.exclusive {
                    release.await()
                    t.categories.insert(Category(0, "restored", 10, 0))
                }
            }
            val create = async(start = CoroutineStart.UNDISPATCHED) { t.create.await("fresh") }
            assertFalse(create.isCompleted)
            assertEquals(0, t.reads)
            release.complete(Unit)
            exclusive.join()
            assertEquals(CreateCategoryWithName.Result.Success, create.await())
            assertEquals(11L, t.categories.getAll().last().order)
        }
    }

    @Test
    fun `cancel library batch before its first write releases admission without relation changes`() = runBlocking {
        database { t ->
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            t.onRelationRead = {
                reached.complete(Unit)
                release.await()
            }
            val call = launch(start = CoroutineStart.UNDISPATCHED) {
                t.membership.awaitAll(
                    listOf(t.manga, t.second),
                    listOf(t.cats[2].id),
                    emptyList(),
                    coordinator.generation,
                )
            }
            reached.await()
            call.cancelAndJoin()
            release.complete(Unit)
            assertEquals(0, t.relations)
            assertEquals(listOf(t.cats[0].id), t.ids(t.manga))
            assertEquals(listOf(t.cats[1].id), t.ids(t.second))
        }
    }

    @Test
    fun `reorders waiting for category mutex are already admitted ahead of exclusive`() = runBlocking {
        database { t ->
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var writes = 0
            t.onCategoryWrite = {
                if (++writes == 1) {
                    reached.complete(Unit)
                    release.await()
                }
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) { t.reorder.await(t.cats[0], 2) }
            reached.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { t.reorder.await(t.cats[1], 2) }
            assertFalse(second.isCompleted)
            var entered = false
            val exclusive = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.exclusive { entered = true } }
            assertFalse(entered)
            release.complete(Unit)
            assertEquals(ReorderCategory.Result.Success, first.await())
            assertEquals(ReorderCategory.Result.Success, second.await())
            exclusive.join()
            assertTrue(entered)
            assertEquals(
                listOf(t.cats[2].id, t.cats[0].id, t.cats[1].id),
                t.categories.getAll().filterNot {
                    it.isSystemCategory
                }.map { it.id },
            )
        }
    }

    @Test
    fun `failed relation replacement rolls back its SQLite transaction and releases admission`() = runBlocking {
        database { t ->
            t.driver.onSql = { sql ->
                if (sql.startsWith("INSERT INTO mangas_categories")) error("SYNTHETIC_WRITE_FAILURE")
            }
            assertFalse(t.membership.await(t.manga, listOf(t.cats[2].id), coordinator.generation))
            t.driver.onSql = {}
            assertEquals(listOf(t.cats[0].id), t.ids(t.manga))
        }
    }

    @Test
    fun `explicit removal preserves categories and unrelated fields and never toggles back`() = runBlocking {
        database { t ->
            t.mangas.update(
                MangaUpdate(t.manga.id, favorite = true, dateAdded = 42, notes = "current", isPrivate = true),
            )
            assertTrue(t.remove.await(t.manga, coordinator.generation) != null)
            val current = t.mangas.getMangaById(t.manga.id)!!
            assertFalse(current.favorite)
            assertEquals(0L, current.dateAdded)
            assertEquals("current", current.notes)
            assertTrue(current.isPrivate)
            assertEquals(listOf(t.cats[0].id), t.ids(t.manga))
            assertEquals(MangaUpdate(t.manga.id, favorite = false, dateAdded = 0), t.updates.single())
            assertEquals(1, t.coverDeletes)
            assertNull(t.remove.await(t.manga.copy(favorite = true), coordinator.generation))
            assertEquals(1, t.coverDeletes)
            assertEquals(1, t.updates.size)
        }
    }

    @Test
    fun `stale removal has no reads writes or cover effects`() = runBlocking {
        database { t ->
            val generation = coordinator.generation
            coordinator.exclusive { t.mangas.update(MangaUpdate(t.manga.id, favorite = true, dateAdded = 42)) }
            assertNull(t.remove.await(t.manga, generation))
            assertEquals(0, t.reads)
            assertEquals(0, t.coverDeletes)
            assertTrue(t.updates.isEmpty())
            assertTrue(t.mangas.getMangaById(t.manga.id)!!.favorite)
        }
    }

    @Test
    fun `removal keeps exclusive blocked through narrow write and derived cover decision`() = runBlocking {
        database { t ->
            t.mangas.update(MangaUpdate(t.manga.id, favorite = true, dateAdded = 42))
            exclusiveWaits(
                install = { barrier -> t.onUpdate = barrier },
                operation = { assertTrue(t.remove.await(t.manga, coordinator.generation) != null) },
            )
            assertEquals(1, t.coverDeletes)
            assertFalse(t.mangas.getMangaById(t.manga.id)!!.favorite)
        }
    }
}
