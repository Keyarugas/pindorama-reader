package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.toBackupManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.ByteArrayOutputStream
import java.io.IOException

class BackupCreatorGuardrailsTest {
    private val context = mockk<Context>()
    private val uri = mockk<Uri>()
    private val favorites = mockk<GetFavorites>()
    private val repository = mockk<MangaRepository>()
    private val mangaCreator = mockk<MangaBackupCreator>()
    private val categories = mockk<CategoriesBackupCreator>()
    private val preferences = mockk<PreferenceBackupCreator>()
    private val stores = mockk<ExtensionStoresBackupCreator>()
    private val sources = mockk<SourcesBackupCreator>()
    private val validator = mockk<BackupFileValidator>()
    private val backupPreferences = BackupPreferences(InMemoryPreferenceStore())
    private val privateManga = Manga.create().copy(id = 1, title = "Private synthetic work", isPrivate = true)

    @BeforeEach
    fun setup() {
        mockkStatic(UniFile::class)
        coEvery { favorites.await() } returns emptyList()
        coEvery { repository.getReadMangaNotInLibrary() } returns emptyList()
        coEvery { mangaCreator(any(), any()) } answers { firstArg<List<Manga>>().map { it.toBackupManga() } }
        coEvery { categories() } returns emptyList()
        coEvery { stores() } returns emptyList()
        coEvery { sources(any()) } returns emptyList()
        every { preferences.createApp(any()) } returns emptyList()
        coEvery { preferences.createSource(any()) } returns emptyList()
    }

    @AfterEach
    fun cleanup() {
        unmockkStatic(UniFile::class)
    }

    private fun creator(automatic: Boolean) = BackupCreator(
        automatic, context, ProtoBuf, favorites, backupPreferences, repository,
        categories, mangaCreator, preferences, stores, sources, validator,
    )

    @Test
    fun `manual and automatic block actual private snapshot before accessing destination`() = runTest {
        coEvery { favorites.await() } returns listOf(privateManga)
        listOf(false, true).forEach { automatic ->
            val error = assertThrows<BackupException> { creator(automatic).backup(uri, BackupOptions()) }
            assertEquals(BackupError.PROTECTION_REQUIRED, error.error)
        }
        verify(exactly = 0) { UniFile.fromUri(any(), any()) }
        coVerify(exactly = 0) { mangaCreator(any(), any()) }
        assertEquals(0L, backupPreferences.lastAutoBackupTimestamp.get())
    }

    @Test
    fun `private nonfavorite is blocked only when read entries are included`() = runTest {
        coEvery { repository.getReadMangaNotInLibrary() } returns listOf(privateManga.copy(favorite = false))
        assertThrows<BackupException> { creator(false).backup(uri, BackupOptions()) }
        verify(exactly = 0) { UniFile.fromUri(any(), any()) }
    }

    @Test
    fun `public conventional backup writes gzip and validates before completing`() = runTest {
        val manga = privateManga.copy(isPrivate = false)
        coEvery { favorites.await() } returns listOf(manga)
        val file = mockk<UniFile>()
        val output = ByteArrayOutputStream()
        every { UniFile.fromUri(context, uri) } returns file
        every { file.isFile } returns true
        every { file.length() } returns 0
        every { file.openOutputStream() } returns output
        every { file.uri } returns uri
        every { uri.toString() } returns "content://synthetic/backup"
        coEvery { validator.validate(uri) } answers {
            assertTrue(output.size() > 2)
            BackupFileValidator.Results(emptyList(), emptyList())
        }
        assertEquals("content://synthetic/backup", creator(false).backup(uri, BackupOptions(readEntries = false)))
        assertEquals(0x1f, output.toByteArray()[0].toInt() and 0xff)
        assertEquals(0x8b, output.toByteArray()[1].toInt() and 0xff)
        coVerify(exactly = 1) { validator.validate(uri) }
        verify(exactly = 0) { file.delete() }
    }

    @Test
    fun `nonempty destination is neither overwritten nor deleted`() = runTest {
        coEvery { favorites.await() } returns listOf(privateManga.copy(isPrivate = false))
        val file = mockk<UniFile>()
        every { UniFile.fromUri(context, uri) } returns file
        every { file.isFile } returns true
        every { file.length() } returns 100
        val error = assertThrows<BackupException> { creator(false).backup(uri, BackupOptions()) }
        assertEquals(BackupError.DESTINATION_NOT_EMPTY, error.error)
        verify(exactly = 0) { file.openOutputStream() }
        verify(exactly = 0) { file.delete() }
    }

    @Test
    fun `automatic retention preserves three predecessors only after new file validates`() = runTest {
        coEvery { favorites.await() } returns listOf(privateManga.copy(isPrivate = false))
        val directory = mockk<UniFile>()
        val file = mockk<UniFile>()
        val fileUri = mockk<Uri>()
        val old = (1..4).map { index ->
            mockk<UniFile> {
                every { name } returns "backup_$index.tachibk"
                every { this@mockk.uri } returns mockk()
                every { delete() } returns true
            }
        }
        every { UniFile.fromUri(context, uri) } returns directory
        every { directory.createFile(any()) } returns file
        every { directory.listFiles(any()) } returns (old + file).toTypedArray()
        every { file.isFile } returns true
        every { file.length() } returns 0
        every { file.openOutputStream() } returns ByteArrayOutputStream()
        every { file.uri } returns fileUri
        every { fileUri.toString() } returns "content://synthetic/new"
        coEvery { validator.validate(fileUri) } returns BackupFileValidator.Results(emptyList(), emptyList())

        creator(true).backup(uri, BackupOptions())

        coVerifyOrder {
            file.openOutputStream()
            validator.validate(fileUri)
            directory.listFiles(any())
            old.first().delete()
        }
        old.drop(1).forEach { previous -> verify(exactly = 0) { previous.delete() } }
        verify(exactly = 0) { file.delete() }
        assertTrue(backupPreferences.lastAutoBackupTimestamp.get() > 0)
    }

    @Test
    fun `automatic validation failure or cancellation never lists or prunes old backups`() = runTest {
        coEvery { favorites.await() } returns listOf(privateManga.copy(isPrivate = false))
        val directory = mockk<UniFile>()
        every { UniFile.fromUri(context, uri) } returns directory
        listOf(IOException("private file"), CancellationException("private title")).forEach { failure ->
            val file = mockk<UniFile>()
            every { directory.createFile(any()) } returns file
            every { file.isFile } returns true
            every { file.length() } returns 0
            every { file.openOutputStream() } returns ByteArrayOutputStream()
            every { file.uri } returns uri
            every { file.delete() } returns true
            coEvery { validator.validate(uri) } throws failure

            assertThrows<Exception> { creator(true).backup(uri, BackupOptions()) }

            verify(exactly = 1) { file.delete() }
        }
        verify(exactly = 0) { directory.listFiles(any()) }
        assertEquals(0L, backupPreferences.lastAutoBackupTimestamp.get())
    }
}
