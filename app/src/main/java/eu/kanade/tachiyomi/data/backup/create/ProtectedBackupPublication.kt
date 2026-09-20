package eu.kanade.tachiyomi.data.backup.create

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.BackupError
import eu.kanade.tachiyomi.data.backup.BackupException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object ProtectedBackupPublication {
    /** Close, then compare every ciphertext byte to the known authenticated envelope. No plaintext I/O. */
    suspend fun publish(file: UniFile, encrypted: ByteArray) {
        currentCoroutineContext().ensureActive()
        if (!file.isFile) throw BackupException(BackupError.IO)
        if (file.length() != 0L) throw BackupException(BackupError.DESTINATION_NOT_EMPTY)
        // Do not trust length alone: a provider may return 0 for an existing nonempty document.
        file.openInputStream().use {
            if (it.read() != -1) throw BackupException(BackupError.DESTINATION_NOT_EMPTY)
        }
        val chunk = ByteArray(8192)
        publishBackup(
            write = {
                protectedBackupOperation {
                    file.openOutputStream().use { output ->
                        var offset = 0
                        while (offset < encrypted.size) {
                            currentCoroutineContext().ensureActive()
                            val count = minOf(chunk.size, encrypted.size - offset)
                            output.write(encrypted, offset, count)
                            offset += count
                        }
                    }
                }
            },
            validate = {
                protectedBackupOperation {
                    file.openInputStream().use { input ->
                        var offset = 0
                        while (offset < encrypted.size) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(chunk, 0, minOf(chunk.size, encrypted.size - offset))
                            if (count <= 0) throw BackupException(BackupError.IO)
                            for (i in 0 until count) {
                                if (chunk[i] != encrypted[offset + i]) throw BackupException(BackupError.IO)
                            }
                            offset += count
                        }
                        if (input.read() != -1) throw BackupException(BackupError.IO)
                    }
                }
            },
            cleanup = {
                // Only the selected, previously empty document. Never inspect/prune other backups.
                try {
                    file.delete()
                } catch (_: Exception) {
                    // Provider may leave a partial ciphertext file; never replace the original error.
                }
            },
            retain = {},
        )
    }
}
