package eu.kanade.tachiyomi.data.backup.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class Argon2Profile(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    fun validate() {
        cryptoRequire(
            memoryKiB in 65536..131072 && iterations in 3..6 && parallelism in 1..4 &&
                memoryKiB.toLong() * iterations <= 393216 && memoryKiB % (4 * parallelism) == 0,
            BackupCryptoError.UNSUPPORTED_PARAMETERS,
        )
    }

    companion object {
        // RFC 9106 section 7.4, second recommended profile; never replaced by test parameters.
        val PRODUCTION = Argon2Profile(65536, 3, 4)
    }
}

/** Unauthenticated public information only. Recognition does not imply authenticity. */
internal data class PindobkIdentification(val version: Int)

internal class PindobkHeader(
    val profile: Argon2Profile,
    val payloadSize: Int,
    val salt: ByteArray,
    val nonce: ByteArray,
)

/** Canonical fixed-width big-endian envelope; see docs/BACKUP_CRYPTO_0.4.md. */
internal object PindobkFormat {
    const val VERSION = 1
    const val HEADER_SIZE = 64
    const val TAG_SIZE = 16
    const val SALT_SIZE = 16
    const val NONCE_SIZE = 12
    const val MAX_PAYLOAD_SIZE = 128 * 1024 * 1024
    const val MAX_FILE_SIZE = MAX_PAYLOAD_SIZE + HEADER_SIZE + TAG_SIZE
    private val magic = byteArrayOf(0x50, 0x49, 0x4e, 0x44, 0x4f, 0x42, 0x4b, 0)

    fun identify(prefix: ByteArray): PindobkIdentification {
        cryptoRequire(
            prefix.size >= 10 && magic.indices.all {
                prefix[it] == magic[it]
            },
            BackupCryptoError.INVALID_FORMAT,
        )
        return PindobkIdentification(ByteBuffer.wrap(prefix, 8, 2).short.toInt() and 0xffff)
    }

    fun encode(header: PindobkHeader): ByteArray {
        header.profile.validate()
        cryptoRequire(header.payloadSize in 0..MAX_PAYLOAD_SIZE, BackupCryptoError.SIZE_LIMIT)
        cryptoRequire(
            header.salt.size == SALT_SIZE && header.nonce.size == NONCE_SIZE,
            BackupCryptoError.INVALID_FORMAT,
        )
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(magic).putShort(VERSION.toShort()).putShort(HEADER_SIZE.toShort())
            .put(1.toByte()).put(1.toByte()).put(0x13.toByte()).put(0.toByte())
            .putInt(header.profile.memoryKiB).putInt(header.profile.iterations).putInt(header.profile.parallelism)
            .putLong(header.payloadSize.toLong()).put(header.salt).put(header.nonce).array()
    }

    fun parse(bytes: ByteArray): PindobkHeader {
        cryptoRequire(bytes.size == HEADER_SIZE, BackupCryptoError.INVALID_FORMAT)
        cryptoRequire(identify(bytes).version == VERSION, BackupCryptoError.UNSUPPORTED_VERSION)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply { position(10) }
        cryptoRequire(buffer.short.toInt() == HEADER_SIZE, BackupCryptoError.INVALID_FORMAT)
        cryptoRequire(buffer.get().toInt() == 1 && buffer.get().toInt() == 1, BackupCryptoError.UNSUPPORTED_ALGORITHM)
        cryptoRequire(buffer.get().toInt() == 0x13, BackupCryptoError.UNSUPPORTED_PARAMETERS)
        cryptoRequire(buffer.get().toInt() == 0, BackupCryptoError.INVALID_FORMAT)
        val profile = Argon2Profile(buffer.int, buffer.int, buffer.int).also { it.validate() }
        val size = buffer.long
        cryptoRequire(size in 0..MAX_PAYLOAD_SIZE.toLong(), BackupCryptoError.SIZE_LIMIT)
        return PindobkHeader(
            profile,
            size.toInt(),
            ByteArray(SALT_SIZE).also { buffer.get(it) },
            ByteArray(NONCE_SIZE).also { buffer.get(it) },
        )
    }
}
