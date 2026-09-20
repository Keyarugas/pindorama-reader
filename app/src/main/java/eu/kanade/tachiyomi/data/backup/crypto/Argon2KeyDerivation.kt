package eu.kanade.tachiyomi.data.backup.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal object Argon2KeyDerivation {
    const val MAX_PASSWORD_CHARS = 1024
    const val MAX_PASSWORD_BYTES = 4096

    /** Strict UTF-8, no normalization, trimming, NUL termination or intermediate immutable String. */
    fun encodePassword(password: CharArray): ByteArray {
        cryptoRequire(password.size in 1..MAX_PASSWORD_CHARS, BackupCryptoError.INVALID_PASSWORD_ENCODING)
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = try {
            encoder.encode(CharBuffer.wrap(password))
        } catch (_: CharacterCodingException) {
            throw BackupCryptoException(BackupCryptoError.INVALID_PASSWORD_ENCODING)
        }
        try {
            cryptoRequire(encoded.remaining() <= MAX_PASSWORD_BYTES, BackupCryptoError.INVALID_PASSWORD_ENCODING)
            return ByteArray(encoded.remaining()).also { encoded.get(it) }
        } finally {
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    fun derive(password: ByteArray, salt: ByteArray, profile: Argon2Profile): ByteArray {
        profile.validate()
        cryptoRequire(salt.size == PindobkFormat.SALT_SIZE, BackupCryptoError.INVALID_FORMAT)
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(profile.memoryKiB).withIterations(profile.iterations)
            .withParallelism(profile.parallelism).withSalt(salt).build()
        val key = ByteArray(32)
        try {
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password, key)
            return key
        } catch (e: Throwable) {
            key.fill(0)
            throw e
        } finally {
            parameters.clear()
        }
    }
}
