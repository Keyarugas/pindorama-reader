package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.buffer
import okio.gzip
import java.io.IOException

/** Resource budgets, not a new format. See docs/backup-guardrails.md for scope and limitations. */
internal object ConventionalBackupReader {
    const val MAX_DECOMPRESSED_BYTES = 64L * 1024 * 1024
    const val MAX_INPUT_BYTES = 128L * 1024 * 1024

    suspend fun decode(
        input: Source,
        parser: ProtoBuf,
        maxDecompressedBytes: Long = MAX_DECOMPRESSED_BYTES,
        maxInputBytes: Long = MAX_INPUT_BYTES,
    ): Backup {
        try {
            val bytes = LimitedSource(input, maxInputBytes).buffer().use { source ->
                val magic = source.peek().readShort().toInt()
                val decoded = when (magic) {
                    0x1f8b -> source.gzip().buffer()
                    0x7b7d, 0x7b22, 0x7b0a -> throw BackupException(BackupError.INVALID_FILE)
                    else -> source
                }
                decoded.use {
                    val result = Buffer()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val remaining = maxDecompressedBytes - result.size
                        val count = it.read(result, minOf(8192L, remaining + 1))
                        if (result.size > maxDecompressedBytes) throw BackupException(BackupError.READ_LIMIT)
                        if (count == -1L) break
                    }
                    result.readByteArray()
                }
            }
            currentCoroutineContext().ensureActive()
            return parser.decodeFromByteArray(Backup.serializer(), bytes)
        } catch (e: BackupException) {
            throw e
        } catch (_: SerializationException) {
            throw BackupException(BackupError.INVALID_FILE)
        } catch (_: IOException) {
            throw BackupException(BackupError.INVALID_FILE)
        } catch (_: IllegalArgumentException) {
            throw BackupException(BackupError.INVALID_FILE)
        }
    }

    private class LimitedSource(source: Source, private val limit: Long) : ForwardingSource(source) {
        private var consumed = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val count = super.read(sink, minOf(byteCount, limit - consumed + 1))
            if (count > 0) consumed += count
            if (consumed > limit) throw BackupException(BackupError.READ_LIMIT)
            return count
        }
    }
}
