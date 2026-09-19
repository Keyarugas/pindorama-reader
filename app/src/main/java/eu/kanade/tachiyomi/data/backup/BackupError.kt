package eu.kanade.tachiyomi.data.backup

import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import tachiyomi.i18n.MR
import java.io.IOException

/** Diagnostics must never retain an exception message, cause, URI or a user-supplied name. */
enum class BackupError(val messageRes: StringResource) {
    PROTECTION_REQUIRED(MR.strings.pindorama_backup_protection_required),
    INVALID_FILE(MR.strings.pindorama_backup_invalid),
    READ_LIMIT(MR.strings.pindorama_backup_read_limit),
    IO(MR.strings.pindorama_backup_io_error),
    DESTINATION_NOT_EMPTY(MR.strings.pindorama_backup_destination_not_empty),
    CANCELLED(MR.strings.restoring_backup_canceled),
    UNKNOWN(MR.strings.pindorama_backup_operation_failed),
    ;

    companion object {
        fun from(error: Exception): BackupError = when (error) {
            is BackupException -> error.error
            is CancellationException -> CANCELLED
            is SerializationException -> INVALID_FILE
            is IOException, is SecurityException -> IO
            else -> UNKNOWN
        }
    }
}

class BackupException(val error: BackupError) : IOException(error.name)

enum class BackupOperation {
    CREATE,
    RESTORE,
    RESTORE_BATCH,
    RESTORE_ENTRY,
    RESTORE_REPOSITORY,
    RESTORE_PREFERENCE,
    INVALIDATE_CACHE,
    RETENTION,
    CLEANUP,
}

/** Fixed vocabulary only; safe for logcat and exportable reports alike. */
fun backupDiagnostic(operation: BackupOperation, error: Exception): String =
    "BACKUP_${operation.name}: ${BackupError.from(error).name}"
