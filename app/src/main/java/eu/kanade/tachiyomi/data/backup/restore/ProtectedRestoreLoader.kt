package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.BackupError
import eu.kanade.tachiyomi.data.backup.BackupException
import eu.kanade.tachiyomi.data.backup.ConventionalBackupReader
import eu.kanade.tachiyomi.data.backup.create.protectedBackupOperation
import eu.kanade.tachiyomi.data.backup.crypto.Argon2Profile
import eu.kanade.tachiyomi.data.backup.crypto.BackupCrypto
import eu.kanade.tachiyomi.data.backup.crypto.BackupCryptoException
import eu.kanade.tachiyomi.data.backup.crypto.CryptoMemoryBudget
import eu.kanade.tachiyomi.data.backup.crypto.PindobkFormat
import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.protobuf.ProtoBuf
import okio.source
import java.io.ByteArrayInputStream
import java.io.InputStream

/** Prepared data is never persisted, logged, exposed in UI State, or reopened from a mutable provider. */
internal class PreparedProtectedRestore private constructor(internal val backup: Backup) {
    companion object {
        internal suspend fun validated(backup: Backup): PreparedProtectedRestore {
            ProtectedRestorePreferences.validate(backup.backupPreferences)
            val identities = mutableSetOf<Pair<Long, String>>()
            backup.backupManga.forEach { manga ->
                currentCoroutineContext().ensureActive()
                requireValid(manga.url.isNotBlank() && identities.add(manga.source to manga.url))
                manga.getMangaImpl() // Validate memo conversion before any database mutation.
                val chapters = mutableSetOf<String>()
                manga.chapters.forEach {
                    currentCoroutineContext().ensureActive()
                    requireValid(it.url.isNotBlank() && chapters.add(it.url))
                    requireValid(it.chapterNumber.isFinite() && it.lastPageRead >= 0)
                    it.toChapterImpl()
                }
                manga.tracking.forEach {
                    requireValid(it.lastChapterRead.isFinite() && it.score.isFinite())
                }
            }
            requireValid(backup.backupCategories.map { it.order }.distinct().size == backup.backupCategories.size)
            return PreparedProtectedRestore(backup)
        }

        private fun requireValid(value: Boolean) {
            if (!value) throw BackupException(BackupError.INVALID_FILE)
        }
    }
}

@Inject
class ProtectedRestoreLoader(private val context: Context, private val parser: ProtoBuf) {
    internal suspend fun isProtected(uri: Uri): Boolean = protectedBackupOperation {
        open(uri).use { recognize(it) }
    }

    internal suspend fun prepare(uri: Uri, password: CharArray): PreparedProtectedRestore =
        open(uri).use { prepare(it, password, parser) }

    private fun open(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw BackupException(BackupError.IO)

    companion object {
        /** Reserve PINDOBK prefixes, including truncated headers. Never retry them as conventional data. */
        internal fun recognize(input: InputStream): Boolean {
            val prefix = ByteArray(PindobkFormat.HEADER_SIZE)
            var size = 0
            while (size < prefix.size) {
                val value = input.read()
                if (value == -1) break
                prefix[size++] = value.toByte()
            }
            if (size == 0) throw BackupException(BackupError.INVALID_FILE)
            val magic = "PINDOBK\u0000".toByteArray(Charsets.US_ASCII)
            val resemblesDamagedMagic = size >= magic.size && magic.indices.count { prefix[it] == magic[it] } >= 6
            if (prefix[0] != 'P'.code.toByte() && !resemblesDamagedMagic) return false
            try {
                PindobkFormat.parse(prefix.copyOf(size))
            } catch (_: BackupCryptoException) {
                throw BackupException(BackupError.PROTECTED_FORMAT)
            }
            return true
        }

        internal suspend fun prepare(
            input: InputStream,
            password: CharArray,
            parser: ProtoBuf = ProtoBuf,
        ): PreparedProtectedRestore = protectedBackupOperation {
            var plaintext: ByteArray? = null
            try {
                // Store inside the interruptible block so cancellation on return cannot orphan a secret buffer.
                runInterruptible { plaintext = BackupCrypto().decrypt(input, password) }
                currentCoroutineContext().ensureActive()
                CryptoMemoryBudget.RUNTIME.check(0, Argon2Profile.PRODUCTION)
                val backup = ByteArrayInputStream(checkNotNull(plaintext)).use {
                    ConventionalBackupReader.decode(it.source(), parser)
                }
                PreparedProtectedRestore.validated(backup)
            } finally {
                plaintext?.fill(0)
                password.fill('\u0000')
            }
        }
    }
}
