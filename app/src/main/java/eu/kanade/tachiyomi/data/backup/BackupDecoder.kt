package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.serialization.protobuf.ProtoBuf
import okio.source

@Inject
class BackupDecoder(
    private val context: Context,
    private val parser: ProtoBuf,
) {
    /** Legacy GZIP or uncompressed Protobuf, with bounded input and expansion. */
    suspend fun decode(uri: Uri): Backup {
        val input = context.contentResolver.openInputStream(uri) ?: throw BackupException(BackupError.IO)
        return input.use { ConventionalBackupReader.decode(it.source(), parser) }
    }
}
