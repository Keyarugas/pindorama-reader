package eu.kanade.tachiyomi.data.backup.crypto

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BackupCryptoTest {
    private val engine = BackupCrypto()
    private val password get() = "Senha sintética 🔒\u0000 e\u0301".toCharArray()

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/backup-crypto/$name")).use { it.readBytes() }

    private fun fixture() = resource("binary-v1.pindobk")

    private fun fails(code: BackupCryptoError, block: () -> Unit) {
        val error = assertThrows<BackupCryptoException>(block)
        assertEquals(code, error.error)
        assertEquals(code.name, error.message)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
    }

    @Test
    fun `independent reference fixtures remain readable with unchanged bytes`() {
        mapOf(
            "binary-v1" to "249a8db49bde95973e7ff1f583587ee2bb18502bc119f430cc10dca27e46fffd",
            "empty-v1" to "8d580cc747d04b45e0b3b031245297e88e3eddc67dae941171c480a0c648fe06",
        ).forEach { (name, digest) ->
            val file = resource("$name.pindobk")
            assertEquals(digest, MessageDigest.getInstance("SHA-256").digest(file).toHexString())
            assertArrayEquals(resource("$name.payload"), engine.decrypt(file, password))
            assertArrayEquals(file.copyOf(64), PindobkFormat.encode(PindobkFormat.parse(file.copyOf(64))))
        }
    }

    @Test
    fun `empty small and multi megabyte opaque payloads round trip between instances`() {
        listOf(0, 1, 15, 16, 17, 8193, 8 * 1024 * 1024).forEach { size ->
            val payload = ByteArray(size) { (it * 31).toByte() }
            val before = payload.copyOf()
            val secret = password
            val file = engine.encrypt(payload, secret)
            assertEquals(size + 80, file.size)
            assertArrayEquals(before, BackupCrypto().decrypt(file, secret))
            assertArrayEquals(before, payload)
            assertArrayEquals(password, secret)
            assertEquals(Argon2Profile.PRODUCTION, PindobkFormat.parse(file.copyOf(64)).profile)
        }
    }

    @Test
    fun `production entropy changes salt nonce and ciphertext for every operation`() {
        val files = List(4) { engine.encrypt(byteArrayOf(1, 2, 3), password) }
        assertEquals(4, files.map { it.copyOfRange(36, 52).toList() }.toSet().size)
        assertEquals(4, files.map { it.copyOfRange(52, 64).toList() }.toSet().size)
        assertEquals(4, files.map { it.toList() }.toSet().size)
    }

    @Test
    fun `wrong password never returns plaintext`() {
        var result: ByteArray? = null
        fails(BackupCryptoError.AUTHENTICATION_FAILED) {
            result = engine.decrypt(fixture(), "wrong synthetic password".toCharArray())
        }
        assertNull(result)
    }

    @Test
    fun `unicode normalization whitespace and nul are not silently changed`() {
        val secret = " é\u0000🔒 ".toCharArray()
        val file = engine.encrypt(byteArrayOf(42), secret)
        assertArrayEquals(byteArrayOf(42), engine.decrypt(file, secret))
        listOf(" e\u0301\u0000🔒 ", "é\u0000🔒", " é").forEach {
            fails(BackupCryptoError.AUTHENTICATION_FAILED) { engine.decrypt(file, it.toCharArray()) }
        }
    }

    @Test
    fun `every truncated prefix and trailing data are rejected`() {
        val file = fixture()
        file.indices.forEach { size ->
            fails(BackupCryptoError.INVALID_FORMAT) { engine.decrypt(file.copyOf(size), password) }
        }
        fails(BackupCryptoError.INVALID_FORMAT) { engine.decrypt(file + byteArrayOf(0), password) }
    }

    @Test
    fun `all header bytes ciphertext and tag are covered by validation or authentication`() {
        val file = fixture()
        // All non-KDF header bytes, plus valid parameter changes in a separate test.
        ((0..15) + (28..63) + listOf(64, file.lastIndex - 16, file.lastIndex)).forEach { offset ->
            val changed = file.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            var returned: ByteArray? = null
            assertThrows<BackupCryptoException> { returned = engine.decrypt(changed, password) }
            assertNull(returned)
        }
    }

    @Test
    fun `valid but tampered kdf parameters fail authentication`() {
        listOf(16 to 65552, 20 to 4, 24 to 2).forEach { (offset, value) ->
            val file = fixture().also { ByteBuffer.wrap(it).putInt(offset, value) }
            fails(BackupCryptoError.AUTHENTICATION_FAILED) { engine.decrypt(file, password) }
        }
    }

    @Test
    fun `identification uses magic and reports version without claiming authenticity`() {
        assertEquals(1, engine.identify(fixture().copyOf(10)).version)
        val unknown = fixture().also { it[9] = 2 }
        assertEquals(2, engine.identify(unknown).version)
        fails(BackupCryptoError.UNSUPPORTED_VERSION) { engine.decrypt(unknown, password) }
        fails(BackupCryptoError.INVALID_FORMAT) { engine.identify("conventional.tachibk".toByteArray()) }
        fails(BackupCryptoError.INVALID_FORMAT) { engine.identify(byteArrayOf()) }
    }

    @Test
    fun `invalid fields and resource requests are rejected before body reads or derivation`() {
        val changes = listOf<Pair<BackupCryptoError, (ByteArray) -> Unit>>(
            BackupCryptoError.UNSUPPORTED_VERSION to { it[9] = 3 },
            BackupCryptoError.INVALID_FORMAT to { it[11] = 65 },
            BackupCryptoError.UNSUPPORTED_ALGORITHM to { it[12] = 2 },
            BackupCryptoError.UNSUPPORTED_ALGORITHM to { it[13] = 2 },
            BackupCryptoError.UNSUPPORTED_PARAMETERS to { it[14] = 16 },
            BackupCryptoError.INVALID_FORMAT to { it[15] = 1 },
            BackupCryptoError.SIZE_LIMIT to { ByteBuffer.wrap(it).putLong(28, Long.MAX_VALUE) },
            BackupCryptoError.SIZE_LIMIT to { ByteBuffer.wrap(it).putLong(28, -1) },
            BackupCryptoError.SIZE_LIMIT to { ByteBuffer.wrap(it).putLong(28, PindobkFormat.MAX_PAYLOAD_SIZE + 1L) },
        ) + listOf(16, 20, 24).flatMap { offset ->
            listOf(0, -1, Int.MAX_VALUE).map { value ->
                BackupCryptoError.UNSUPPORTED_PARAMETERS to { bytes: ByteArray ->
                    ByteBuffer.wrap(bytes).putInt(offset, value)
                    Unit
                }
            }
        }
        changes.forEach { (code, change) ->
            val header = fixture().copyOf(64).also(change)
            val noResources = BackupCrypto { _, _ -> throw AssertionError("Resource check must not run") }
            fails(code) { noResources.decrypt(headerOnly(header), password) }
        }
    }

    @Test
    fun `claimed maximum is not allocated before actual bytes arrive`() {
        val header = fixture().copyOf(64).also {
            ByteBuffer.wrap(it).putLong(28, PindobkFormat.MAX_PAYLOAD_SIZE.toLong())
        }
        // Disable only the admission estimate; no low-cost KDF profile is introduced.
        val engine = BackupCrypto { _, _ -> }
        fails(BackupCryptoError.INVALID_FORMAT) { engine.decrypt(header, password) }
    }

    @Test
    fun `memory rejection and allocation failure are controlled without fallback`() {
        val header = fixture().copyOf(64)
        val denied = BackupCrypto { _, profile ->
            assertEquals(Argon2Profile.PRODUCTION, profile)
            throw BackupCryptoException(BackupCryptoError.INSUFFICIENT_RESOURCES)
        }
        fails(BackupCryptoError.INSUFFICIENT_RESOURCES) { denied.decrypt(headerOnly(header), password) }
        fails(BackupCryptoError.INSUFFICIENT_RESOURCES) { denied.encrypt(byteArrayOf(), password) }
        val allocationFailure = BackupCrypto { _, _ -> throw OutOfMemoryError("synthetic sensitive detail") }
        fails(BackupCryptoError.INSUFFICIENT_RESOURCES) { allocationFailure.encrypt(byteArrayOf(), password) }
        assertEquals(144L * 1024 * 1024, CryptoMemoryBudget.requiredBytes(0, Argon2Profile.PRODUCTION))
        assertEquals(656L * 1024 * 1024, CryptoMemoryBudget.requiredBytes(128 * 1024 * 1024, Argon2Profile.PRODUCTION))
    }

    @Test
    fun `io errors are generic and streams remain caller owned`() {
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("synthetic secret URL password")
        }
        fails(BackupCryptoError.IO_ERROR) { engine.decrypt(input, password) }
        var closed = false
        val output = object : OutputStream() {
            override fun write(b: Int) = throw IOException("synthetic private filename")
            override fun close() {
                closed = true
            }
        }
        fails(BackupCryptoError.IO_ERROR) { engine.encryptTo(byteArrayOf(), password, output) }
        assertFalse(closed)
        val bytes = ByteArrayOutputStream()
        engine.encryptTo(byteArrayOf(3), password, bytes)
        assertArrayEquals(byteArrayOf(3), engine.decrypt(bytes.toByteArray(), password))
    }

    @Test
    fun `partial destination writes contain only incomplete encrypted envelope and generic errors`() {
        val written = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(b: Int) {
                if (written.size() == 70) throw SecurityException("synthetic private destination")
                written.write(b)
            }
        }
        fails(BackupCryptoError.IO_ERROR) { engine.encryptTo(ByteArray(256) { 42 }, password, output) }
        assertEquals(70, written.size())
        assertEquals(1, engine.identify(written.toByteArray()).version)
        fails(BackupCryptoError.INVALID_FORMAT) { engine.decrypt(written.toByteArray(), password) }
        val exhausted = object : OutputStream() {
            override fun write(b: Int): Unit = throw OutOfMemoryError("synthetic destination detail")
        }
        fails(BackupCryptoError.INSUFFICIENT_RESOURCES) { engine.encryptTo(byteArrayOf(), password, exhausted) }
    }

    @Test
    fun `partial reads including zero byte reads do not bypass verification`() {
        val original = ByteArrayInputStream(fixture())
        val input = object : InputStream() {
            var calls = 0
            override fun read() = original.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                if (calls++ % 2 == 0) 0 else original.read(b, off, minOf(len, 3))
        }
        assertArrayEquals(resource("binary-v1.payload"), engine.decrypt(input, password))
    }

    @Test
    fun `interrupted operation stops before resources and preserves interruption`() {
        val noResources = BackupCrypto { _, _ -> throw AssertionError("KDF admission must not run") }
        try {
            Thread.currentThread().interrupt()
            val error = assertThrows<CancellationException> { noResources.encrypt(byteArrayOf(), password) }
            assertEquals("CANCELLED", error.message)
            assertNull(error.cause)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertArrayEquals(byteArrayOf(), engine.decrypt(resource("empty-v1.pindobk"), password))
    }

    @Test
    fun `stream cancellation remains cancellation without sensitive diagnostics`() {
        val input = object : InputStream() {
            override fun read(): Int = throw CancellationException("synthetic secret location")
        }
        val error = assertThrows<CancellationException> { engine.decrypt(input, password) }
        assertEquals("CANCELLED", error.message)
        assertNull(error.cause)
        val output = object : OutputStream() {
            override fun write(b: Int): Unit = throw CancellationException("synthetic private destination")
        }
        val writeError = assertThrows<CancellationException> { engine.encryptTo(byteArrayOf(), password, output) }
        assertEquals("CANCELLED", writeError.message)
        assertNull(writeError.cause)
    }

    @Test
    fun `interruption after authentication wipes private result and never returns it`() {
        var privateBuffer: ByteArray? = null
        var returned: ByteArray? = null
        mockkObject(BackupAesGcm)
        try {
            every { BackupAesGcm.decrypt(any(), any(), any(), any()) } answers {
                (callOriginal() as ByteArray).also {
                    privateBuffer = it
                    Thread.currentThread().interrupt()
                }
            }
            assertThrows<CancellationException> { returned = engine.decrypt(fixture(), password) }
        } finally {
            Thread.interrupted()
            unmockkObject(BackupAesGcm)
        }
        assertNull(returned)
        assertArrayEquals(ByteArray(resource("binary-v1.payload").size), privateBuffer)
    }

    @Test
    fun `concurrent instances cannot multiply kdf memory and failed operation releases gate`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit {
                val input = object : InputStream() {
                    override fun read(): Int {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        return -1
                    }
                }
                fails(BackupCryptoError.INVALID_FORMAT) { engine.decrypt(input, password) }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fails(BackupCryptoError.BUSY) { BackupCrypto().encrypt(byteArrayOf(), password) }
            release.countDown()
            task.get(10, TimeUnit.SECONDS)
            assertArrayEquals(byteArrayOf(), engine.decrypt(resource("empty-v1.pindobk"), password))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun headerOnly(header: ByteArray): InputStream = object : InputStream() {
        private var offset = 0
        override fun read(): Int {
            if (offset >= header.size) throw AssertionError("Body must not be read")
            return header[offset++].toInt() and 255
        }
    }
}
