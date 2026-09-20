package eu.kanade.tachiyomi.data.backup.create

import eu.kanade.tachiyomi.data.backup.BackupError
import eu.kanade.tachiyomi.data.backup.BackupException
import eu.kanade.tachiyomi.data.backup.ConventionalBackupReader
import eu.kanade.tachiyomi.data.backup.crypto.Argon2KeyDerivation
import eu.kanade.tachiyomi.data.backup.crypto.Argon2Profile
import eu.kanade.tachiyomi.data.backup.crypto.BackupCrypto
import eu.kanade.tachiyomi.data.backup.crypto.BackupCryptoError
import eu.kanade.tachiyomi.data.backup.crypto.BackupCryptoException
import eu.kanade.tachiyomi.data.backup.crypto.CryptoMemoryBudget
import eu.kanade.tachiyomi.data.backup.models.Backup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

internal object ProtectedBackupPayload {
    // This also leaves 128 MiB + 16 MiB for serialization buffers after the model is built.
    // It is admission, not a heap reservation or an exact prediction of the object graph.
    fun checkResources() = CryptoMemoryBudget.RUNTIME.check(0, Argon2Profile.PRODUCTION)

    suspend fun encode(backup: Backup, parser: ProtoBuf): ByteArray {
        currentCoroutineContext().ensureActive()
        checkResources()
        val serialized = parser.encodeToByteArray(Backup.serializer(), backup)
        try {
            if (serialized.isEmpty()) throw BackupException(BackupError.INVALID_FILE)
            if (serialized.size > ConventionalBackupReader.MAX_DECOMPRESSED_BYTES) {
                throw BackupException(BackupError.READ_LIMIT)
            }
            return compress(serialized)
        } finally {
            serialized.fill(0)
        }
    }

    internal suspend fun compress(serialized: ByteArray): ByteArray {
        if (serialized.size > ConventionalBackupReader.MAX_DECOMPRESSED_BYTES) {
            throw BackupException(BackupError.READ_LIMIT)
        }
        val bytes = LimitedMemoryOutput(ConventionalBackupReader.MAX_INPUT_BYTES)
        try {
            GZIPOutputStream(bytes, 8192).use { gzip ->
                var offset = 0
                while (offset < serialized.size) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(8192, serialized.size - offset)
                    gzip.write(serialized, offset, count)
                    offset += count
                }
            }
            currentCoroutineContext().ensureActive()
            return bytes.toByteArray()
        } finally {
            bytes.erase()
        }
    }

    suspend fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        // Tie coroutine cancellation to the crypto engine's thread-interruption checks.
        currentCoroutineContext().ensureActive()
        return runInterruptible { BackupCrypto().encrypt(payload, password) }.also {
            currentCoroutineContext().ensureActive()
        }
    }
}

/** Owned compressed plaintext, never a filesystem stream; clear the current backing array. */
internal class LimitedMemoryOutput(private val limit: Long) : ByteArrayOutputStream(8192) {
    override fun write(b: Int) {
        checkSize(1)
        super.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        checkSize(len)
        super.write(b, off, len)
    }

    private fun checkSize(extra: Int) {
        if (count.toLong() + extra > limit) throw BackupException(BackupError.READ_LIMIT)
    }

    fun erase() = buf.fill(0)
}

/** No trimming or normalization; returned array is owned by the caller. */
internal fun confirmedBackupPassword(password: CharArray, confirmation: CharArray): CharArray {
    if (!password.contentEquals(confirmation)) throw BackupException(BackupError.PASSWORD_MISMATCH)
    try {
        Argon2KeyDerivation.encodePassword(password).fill(0)
    } catch (_: BackupCryptoException) {
        throw BackupException(BackupError.INVALID_PASSWORD)
    }
    return password.copyOf()
}

internal suspend fun <T> protectedBackupOperation(block: suspend () -> T): T {
    try {
        return block()
    } catch (_: CancellationException) {
        throw CancellationException("CANCELLED")
    } catch (e: BackupException) {
        // A stream close may have attached a sensitive suppressed exception to this fixed-code error.
        throw BackupException(e.error)
    } catch (e: BackupCryptoException) {
        throw BackupException(
            when (e.error) {
                BackupCryptoError.INSUFFICIENT_RESOURCES -> BackupError.INSUFFICIENT_RESOURCES
                BackupCryptoError.SIZE_LIMIT -> BackupError.READ_LIMIT
                BackupCryptoError.INVALID_PASSWORD_ENCODING -> BackupError.INVALID_PASSWORD
                BackupCryptoError.BUSY -> BackupError.BUSY
                else -> BackupError.UNKNOWN
            },
        )
    } catch (_: OutOfMemoryError) {
        throw BackupException(BackupError.INSUFFICIENT_RESOURCES)
    } catch (e: Exception) {
        throw BackupException(BackupError.from(e))
    }
}
