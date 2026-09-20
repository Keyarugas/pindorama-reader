package eu.kanade.tachiyomi.data.backup.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CryptoPrimitivesTest {
    @Test
    fun `argon2id matches RFC 9106 section 5_3 vector`() {
        // The RFC vector's 32 KiB is NOT the production engine's configuration.
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(32).withIterations(3).withParallelism(4)
            .withSalt(ByteArray(16) { 2 }).withSecret(ByteArray(8) { 3 })
            .withAdditional(ByteArray(12) { 4 }).build()
        val result = ByteArray(32)
        Argon2BytesGenerator().apply { init(parameters) }.generateBytes(ByteArray(32) { 1 }, result)
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", result.toHexString())
        parameters.clear()
    }

    @Test
    fun `aes256 gcm matches McGrew Viega public test case 14`() {
        val expected = ("cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919").hexToByteArray()
        assertArrayEquals(expected, BackupAesGcm.encrypt(ByteArray(16), ByteArray(32), ByteArray(12), byteArrayOf()))
        assertArrayEquals(ByteArray(16), BackupAesGcm.decrypt(expected, ByteArray(32), ByteArray(12), byteArrayOf()))
        val error = assertThrows<BackupCryptoException> {
            BackupAesGcm.decrypt(expected, ByteArray(32), ByteArray(12), byteArrayOf(1))
        }
        assertEquals(BackupCryptoError.AUTHENTICATION_FAILED, error.error)
    }

    @Test
    fun `password encoding is strict utf8 including nul and supplementary characters`() {
        assertEquals("41c3a900f09f9492", Argon2KeyDerivation.encodePassword("Aé\u0000🔒".toCharArray()).toHexString())
        assertArrayEquals(ByteArray(1024) { 65 }, Argon2KeyDerivation.encodePassword(CharArray(1024) { 'A' }))
        listOf(charArrayOf(), CharArray(1025) { 'a' }, charArrayOf('\uD800'), charArrayOf('\uDC00')).forEach {
            val error = assertThrows<BackupCryptoException> { Argon2KeyDerivation.encodePassword(it) }
            assertEquals(BackupCryptoError.INVALID_PASSWORD_ENCODING, error.error)
        }
    }

    @Test
    fun `production profile and future accepted profiles stay inside explicit work budget`() {
        assertEquals(Argon2Profile(65536, 3, 4), Argon2Profile.PRODUCTION)
        listOf(Argon2Profile.PRODUCTION, Argon2Profile(65536, 6, 1), Argon2Profile(131072, 3, 4))
            .forEach { it.validate() }
        listOf(
            Argon2Profile(32, 3, 4),
            Argon2Profile(65536, 2, 4),
            Argon2Profile(65536, 3, 5),
            Argon2Profile(131072, 4, 4),
            Argon2Profile(65537, 3, 4),
            Argon2Profile(131073, 3, 4),
        ).forEach {
            val error = assertThrows<BackupCryptoException> { it.validate() }
            assertEquals(BackupCryptoError.UNSUPPORTED_PARAMETERS, error.error)
        }
    }
}
