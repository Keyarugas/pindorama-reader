package eu.kanade.tachiyomi.data.backup.crypto

import okio.Buffer
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore

/**
 * Isolated, synchronous, opaque-payload engine. Call off the main thread.
 * Caller owns streams and input arrays and must not mutate inputs during the call.
 * No plaintext output stream exists: decrypt returns only after full authentication.
 */
internal class BackupCrypto(private val memoryBudget: CryptoMemoryBudget = CryptoMemoryBudget.RUNTIME) {
    fun identify(prefix: ByteArray): PindobkIdentification = PindobkFormat.identify(prefix)

    /** Returns the complete bytes of a .pindobk file. Production entropy is never injectable. */
    fun encrypt(payload: ByteArray, password: CharArray): ByteArray = operation {
        cryptoRequire(payload.size <= PindobkFormat.MAX_PAYLOAD_SIZE, BackupCryptoError.SIZE_LIMIT)
        val profile = Argon2Profile.PRODUCTION
        memoryBudget.check(payload.size, profile)
        val passwordBytes = Argon2KeyDerivation.encodePassword(password)
        var snapshot: ByteArray? = null
        var key: ByteArray? = null
        try {
            snapshot = payload.copyOf()
            val random = SecureRandom()
            val header = PindobkHeader(
                profile,
                snapshot.size,
                ByteArray(PindobkFormat.SALT_SIZE).also(random::nextBytes),
                ByteArray(PindobkFormat.NONCE_SIZE).also(random::nextBytes),
            )
            val aad = PindobkFormat.encode(header)
            checkCryptoCancellation()
            key = Argon2KeyDerivation.derive(passwordBytes, header.salt, profile)
            checkCryptoCancellation()
            val ciphertext = BackupAesGcm.encrypt(snapshot, key, header.nonce, aad)
            checkCryptoCancellation()
            aad + ciphertext
        } finally {
            passwordBytes.fill(0)
            key?.fill(0)
            snapshot?.fill(0)
        }
    }

    /** Writes only ciphertext; caller closes/publishes output and handles any partial write. */
    fun encryptTo(payload: ByteArray, password: CharArray, output: OutputStream) {
        val file = encrypt(payload, password)
        try {
            checkCryptoCancellation()
            output.write(file)
            checkCryptoCancellation()
        } catch (_: CancellationException) {
            throw CancellationException("CANCELLED")
        } catch (_: IOException) {
            throw BackupCryptoException(BackupCryptoError.IO_ERROR)
        } catch (_: RuntimeException) {
            throw BackupCryptoException(BackupCryptoError.IO_ERROR)
        } catch (_: OutOfMemoryError) {
            throw BackupCryptoException(BackupCryptoError.INSUFFICIENT_RESOURCES)
        }
    }

    fun decrypt(file: ByteArray, password: CharArray): ByteArray {
        cryptoRequire(file.size <= PindobkFormat.MAX_FILE_SIZE, BackupCryptoError.SIZE_LIMIT)
        return decrypt(ByteArrayInputStream(file), password)
    }

    fun decrypt(input: InputStream, password: CharArray): ByteArray = operation {
        val aad = ByteArray(PindobkFormat.HEADER_SIZE)
        readFully(input, aad)
        val header = PindobkFormat.parse(aad)
        memoryBudget.check(header.payloadSize, header.profile)
        val passwordBytes = Argon2KeyDerivation.encodePassword(password)
        var key: ByteArray? = null
        try {
            // Collect bounded ciphertext in chunks, not a huge allocation based on an untrusted claim.
            val ciphertext = readCiphertext(input, header.payloadSize + PindobkFormat.TAG_SIZE)
            checkCryptoCancellation()
            key = Argon2KeyDerivation.derive(passwordBytes, header.salt, header.profile)
            checkCryptoCancellation()
            val plaintext = BackupAesGcm.decrypt(ciphertext, key, header.nonce, aad)
            try {
                checkCryptoCancellation()
                plaintext
            } catch (e: Throwable) {
                plaintext.fill(0)
                throw e
            }
        } finally {
            passwordBytes.fill(0)
            key?.fill(0)
        }
    }

    private fun readCiphertext(input: InputStream, size: Int): ByteArray {
        val buffer = Buffer()
        val chunk = ByteArray(8192)
        var remaining = size
        while (remaining > 0) {
            checkCryptoCancellation()
            val count = input.read(chunk, 0, minOf(chunk.size, remaining))
            if (count < 0) throw BackupCryptoException(BackupCryptoError.INVALID_FORMAT)
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) throw BackupCryptoException(BackupCryptoError.INVALID_FORMAT)
                buffer.writeByte(byte)
                remaining--
            } else {
                buffer.write(chunk, 0, count)
                remaining -= count
            }
        }
        cryptoRequire(input.read() == -1, BackupCryptoError.INVALID_FORMAT)
        return buffer.readByteArray()
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            checkCryptoCancellation()
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw BackupCryptoException(BackupCryptoError.INVALID_FORMAT)
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) throw BackupCryptoException(BackupCryptoError.INVALID_FORMAT)
                target[offset++] = byte.toByte()
            } else {
                offset += count
            }
        }
    }

    private fun <T> operation(block: () -> T): T {
        checkCryptoCancellation()
        cryptoRequire(gate.tryAcquire(), BackupCryptoError.BUSY)
        try {
            return block()
        } catch (e: BackupCryptoException) {
            throw e
        } catch (_: CancellationException) {
            throw CancellationException("CANCELLED")
        } catch (_: OutOfMemoryError) {
            throw BackupCryptoException(BackupCryptoError.INSUFFICIENT_RESOURCES)
        } catch (_: EOFException) {
            throw BackupCryptoException(BackupCryptoError.INVALID_FORMAT)
        } catch (_: IOException) {
            throw BackupCryptoException(BackupCryptoError.IO_ERROR)
        } catch (_: GeneralSecurityException) {
            throw BackupCryptoException(BackupCryptoError.CRYPTO_UNAVAILABLE)
        } catch (_: RuntimeException) {
            throw BackupCryptoException(BackupCryptoError.CRYPTO_UNAVAILABLE)
        } catch (_: LinkageError) {
            throw BackupCryptoException(BackupCryptoError.CRYPTO_UNAVAILABLE)
        } finally {
            gate.release()
        }
    }

    companion object {
        // Bound aggregate Argon2 memory across independent engine instances; fail instead of queueing secrets.
        private val gate = Semaphore(1)
    }
}

/** Cooperative boundaries only; cannot preempt a provider's KDF, cipher or blocking I/O. */
private fun checkCryptoCancellation() {
    if (Thread.currentThread().isInterrupted) throw CancellationException("CANCELLED")
}
