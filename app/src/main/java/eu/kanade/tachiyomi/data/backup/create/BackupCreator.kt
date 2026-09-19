package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupError
import eu.kanade.tachiyomi.data.backup.BackupException
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.BackupOperation
import eu.kanade.tachiyomi.data.backup.ConventionalBackupReader
import eu.kanade.tachiyomi.data.backup.backupDiagnostic
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.time.Clock

@AssistedInject
class BackupCreator(
    @Assisted private val isAutoBackup: Boolean,
    private val context: Context,
    private val parser: ProtoBuf,
    private val getFavorites: GetFavorites,
    private val backupPreferences: BackupPreferences,
    private val mangaRepository: MangaRepository,
    private val categoriesBackupCreator: CategoriesBackupCreator,
    private val mangaBackupCreator: MangaBackupCreator,
    private val preferenceBackupCreator: PreferenceBackupCreator,
    private val extensionStoresBackupCreator: ExtensionStoresBackupCreator,
    private val sourcesBackupCreator: SourcesBackupCreator,
    private val backupFileValidator: BackupFileValidator,
) {
    @AssistedFactory
    fun interface Factory {
        fun create(isAutoBackup: Boolean): BackupCreator
    }

    suspend fun backup(uri: Uri, options: BackupOptions): String {
        // Use the same snapshot for the policy and serialization. Never consult UI visibility.
        val mangas = if (options.libraryEntries) {
            getFavorites.await() + if (options.readEntries) mangaRepository.getReadMangaNotInLibrary() else emptyList()
        } else {
            emptyList()
        }
        ConventionalBackupPolicy.check(mangas, options)
        val backupManga = backupMangas(mangas, options)
        val backup = Backup(
            backupManga = backupManga,
            backupCategories = backupCategories(options),
            backupSources = backupSources(backupManga),
            backupPreferences = backupAppPreferences(options),
            backupExtensionStores = backupExtensionStores(options),
            backupSourcePreferences = backupSourcePreferences(options),
        )
        val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)
        if (byteArray.isEmpty()) throw BackupException(BackupError.INVALID_FILE)
        if (byteArray.size > ConventionalBackupReader.MAX_DECOMPRESSED_BYTES) {
            throw BackupException(BackupError.READ_LIMIT)
        }
        currentCoroutineContext().ensureActive()

        val dir = if (isAutoBackup) UniFile.fromUri(context, uri) else null
        // Random suffix prevents a retry in the same minute from overwriting a valid copy.
        val file = if (isAutoBackup) {
            dir?.createFile(getFilename())
        } else {
            UniFile.fromUri(context, uri)
        } ?: throw BackupException(BackupError.IO)
        if (!file.isFile) throw BackupException(BackupError.IO)
        // Do not overwrite or delete an existing nonempty destination, including provider collisions.
        if (file.length() != 0L) throw BackupException(BackupError.DESTINATION_NOT_EMPTY)

        publishBackup(
            write = {
                file.openOutputStream()
                    .also { (it as? FileOutputStream)?.channel?.truncate(0) }
                    .sink().gzip().buffer().use { sink ->
                        var offset = 0
                        while (offset < byteArray.size) {
                            currentCoroutineContext().ensureActive()
                            val count = minOf(8192, byteArray.size - offset)
                            sink.write(byteArray, offset, count)
                            offset += count
                        }
                    }
            },
            validate = { backupFileValidator.validate(file.uri) },
            cleanup = {
                try {
                    file.delete()
                } catch (e: Exception) {
                    logcat(LogPriority.WARN) { backupDiagnostic(BackupOperation.CLEANUP, e) }
                }
            },
            retain = {
                if (isAutoBackup) {
                    backupPreferences.lastAutoBackupTimestamp.set(Clock.System.now().toEpochMilliseconds())
                    // Always preserve the newly validated copy, even if pruning fails midway.
                    try {
                        dir?.listFiles { _, filename -> FILENAME_REGEX.matches(filename) }
                            .orEmpty()
                            .filter { it.uri != file.uri }
                            .sortedByDescending { it.name }
                            .drop(MAX_AUTO_BACKUPS - 1)
                            .forEach {
                                currentCoroutineContext().ensureActive()
                                it.delete()
                            }
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        logcat(LogPriority.WARN) { backupDiagnostic(BackupOperation.RETENTION, e) }
                    }
                }
            },
        )
        return file.uri.toString()
    }

    private suspend fun backupCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return categoriesBackupCreator()
    }

    private suspend fun backupMangas(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        if (!options.libraryEntries) return emptyList()

        return mangaBackupCreator(mangas, options)
    }

    private suspend fun backupSources(mangas: List<BackupManga>): List<BackupSource> {
        return sourcesBackupCreator(mangas)
    }

    private fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()

        return preferenceBackupCreator.createApp(includePrivatePreferences = options.privateSettings)
    }

    private suspend fun backupExtensionStores(options: BackupOptions): List<BackupExtensionStore> {
        if (!options.extensionStores) return emptyList()

        return extensionStoresBackupCreator()
    }

    private suspend fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
        if (!options.sourceSettings) return emptyList()

        return preferenceBackupCreator.createSource(includePrivatePreferences = options.privateSettings)
    }

    companion object {
        private const val MAX_AUTO_BACKUPS: Int = 4
        private val FILENAME_REGEX = """${Regex.escape(
            BuildConfig.APPLICATION_ID,
        )}_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}(?:_[a-f0-9-]{36})?\.tachibk""".toRegex()

        fun getFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())
            return "${BuildConfig.APPLICATION_ID}_${date}_${UUID.randomUUID()}.tachibk"
        }
    }
}
