package eu.kanade.tachiyomi.data.backup

import androidx.lifecycle.viewModelScope
import com.hippo.unifile.UniFile
import eu.kanade.presentation.more.settings.screen.data.CreateBackupViewModel
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.LimitedMemoryOutput
import eu.kanade.tachiyomi.data.backup.create.ProtectedBackupPayload
import eu.kanade.tachiyomi.data.backup.create.ProtectedBackupPublication
import eu.kanade.tachiyomi.data.backup.create.confirmedBackupPassword
import eu.kanade.tachiyomi.data.backup.create.protectedBackupOperation
import eu.kanade.tachiyomi.data.backup.crypto.BackupCrypto
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
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
import java.io.OutputStream

class ProtectedBackupCreateTest {
    private val password get() = "Senha sintética 🔒".toCharArray()

    @Test
    fun `cancellation before coroutine starts clears busy state and cannot announce success`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val factory = mockk<BackupCreator.Factory>()
            val model = CreateBackupViewModel(factory)
            assertTrue(model.prepare(password, password))
            model.viewModelScope.cancel()
            assertFalse(model.createBackup(mockk(), mockk()))
            model.state.first { !it.working }
            assertTrue(model.state.value.cancelled)
            assertFalse(model.state.value.complete)
            verify(exactly = 0) { factory.create(any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `protected is default and mismatched confirmation is rejected without saving passwords`() {
        val factory = mockk<BackupCreator.Factory>()
        val model = CreateBackupViewModel(factory)
        assertTrue(model.state.value.protectedBackup)
        val first = password
        val second = "different".toCharArray()
        assertFalse(model.prepare(first, second))
        assertTrue(first.all { it == '\u0000' })
        assertTrue(second.all { it == '\u0000' })
        assertEquals(BackupError.PASSWORD_MISMATCH, model.state.value.error)
        assertFalse(model.state.value.waitingForDestination)
        verify(exactly = 0) { factory.create(any()) }
    }

    @Test
    fun `confirmed unicode is preserved exactly and pending destination can be discarded`() {
        val secret = confirmedBackupPassword(password, password)
        assertArrayEquals(password, secret)
        val model = CreateBackupViewModel(mockk())
        assertTrue(model.prepare(password, password))
        assertTrue(model.state.value.waitingForDestination)
        assertFalse(model.state.value.toString().contains("Senha"))
        model.discardPrepared()
        assertFalse(model.state.value.waitingForDestination)
        model.setProtected(false)
        assertFalse(model.state.value.protectedBackup)
        assertTrue(model.prepare(charArrayOf(), charArrayOf()))
        model.discardPrepared()
    }

    @Test
    fun `empty invalid encoding and overlong password are rejected`() {
        listOf(charArrayOf(), charArrayOf('\uD800'), CharArray(1025) { 'x' }).forEach {
            val error = assertThrows<BackupException> { confirmedBackupPassword(it, it) }
            assertEquals(BackupError.INVALID_PASSWORD, error.error)
            assertNull(error.cause)
        }
    }

    @Test
    fun `serialization gzip and crypto preserve existing schema and private flag`() = runTest {
        val backup = Backup(listOf(BackupManga(1, "/synthetic", title = "Private synthetic title", isPrivate = true)))
        val gzip = ProtectedBackupPayload.encode(backup, ProtoBuf)
        val file = ProtectedBackupPayload.encrypt(gzip, password)
        val restoredGzip = BackupCrypto().decrypt(file, password)
        assertArrayEquals(gzip, restoredGzip)
        val restored = ConventionalBackupReader.decode(Buffer().write(restoredGzip), ProtoBuf)
        assertTrue(restored.backupManga.single().isPrivate)
        assertEquals("Private synthetic title", restored.backupManga.single().title)
        assertFalse(file.toString(Charsets.ISO_8859_1).contains("Private synthetic title"))
    }

    @Test
    fun `independent reference gzip protobuf fixture agrees with the new creation payload`() = runTest {
        val file = checkNotNull(
            javaClass.getResourceAsStream("/backup-protected-create/private-gzip-v1.pindobk"),
        ).use { it.readBytes() }
        assertEquals(
            "031cc80f2a391e32491094ba1af6460ef3cfa258b005d12dba88e8d984a406d7",
            java.security.MessageDigest.getInstance("SHA-256").digest(file).toHexString(),
        )
        val password = "Senha sintética 🔒\u0000 e\u0301".toCharArray()
        val reference = ConventionalBackupReader.decode(
            Buffer().write(BackupCrypto().decrypt(file, password)),
            ProtoBuf,
        )
        assertTrue(reference.backupManga.single().isPrivate)
        val payload = ProtectedBackupPayload.encode(reference, ProtoBuf)
        val produced = ProtectedBackupPayload.encrypt(payload, password)
        val destination = Destination()
        ProtectedBackupPublication.publish(destination.file, produced)
        val actual = ConventionalBackupReader.decode(
            Buffer().write(BackupCrypto().decrypt(destination.bytes.toByteArray(), password)),
            ProtoBuf,
        )
        assertTrue(actual.backupManga.single().isPrivate)
        assertEquals(reference.backupManga.single().title, actual.backupManga.single().title)
        assertEquals(reference.backupManga.single().url, actual.backupManga.single().url)
    }

    @Test
    fun `independent motor fixture is copied unchanged by protected publication`() = runTest {
        val file = checkNotNull(javaClass.getResourceAsStream("/backup-crypto/binary-v1.pindobk")).use {
            it.readBytes()
        }
        val expected = checkNotNull(javaClass.getResourceAsStream("/backup-crypto/binary-v1.payload")).use {
            it.readBytes()
        }
        val destination = Destination()
        ProtectedBackupPublication.publish(destination.file, file)
        assertTrue(destination.closed)
        assertArrayEquals(file, destination.bytes.toByteArray())
        val decrypted = BackupCrypto().decrypt(
            destination.bytes.toByteArray(),
            "Senha sintética 🔒\u0000 e\u0301".toCharArray(),
        )
        assertArrayEquals(expected, decrypted)
        assertFalse(destination.deleted)
    }

    @Test
    fun `provider write close validation and cleanup failures preserve other backups`() = runTest {
        listOf("write", "close", "corrupt", "truncated", "extra", "read", "cleanup").forEach { failure ->
            val destination = Destination(failure)
            val error = assertThrows<BackupException> {
                protectedBackupOperation {
                    ProtectedBackupPublication.publish(destination.file, ByteArray(20000) { 7 })
                }
            }
            assertEquals(BackupError.IO, error.error)
            assertEquals("IO", error.message)
            assertNull(error.cause)
            assertTrue(destination.deleted)
            assertArrayEquals(byteArrayOf(10, 20, 30), destination.previous)
            verify(exactly = 0) { destination.file.listFiles() }
        }
    }

    @Test
    fun `nonempty document even with lying length is never overwritten or deleted`() = runTest {
        val destination = Destination()
        destination.bytes.write(byteArrayOf(1, 2, 3))
        val error =
            assertThrows<BackupException> { ProtectedBackupPublication.publish(destination.file, byteArrayOf(4)) }
        assertEquals(BackupError.DESTINATION_NOT_EMPTY, error.error)
        assertFalse(destination.deleted)
        assertArrayEquals(byteArrayOf(1, 2, 3), destination.bytes.toByteArray())
        verify(exactly = 0) { destination.file.openOutputStream() }
    }

    @Test
    fun `cancellation before publication cannot touch any destination`() = runTest {
        val destination = Destination()
        val task = async {
            currentCoroutineContext().cancel()
            ProtectedBackupPublication.publish(destination.file, byteArrayOf(1))
        }
        assertThrows<CancellationException> { task.await() }
        verify(exactly = 0) { destination.file.openOutputStream() }
        assertFalse(destination.deleted)
    }

    @Test
    fun `cancellation during write and after close never completes or deletes previous backup`() = runTest {
        listOf("cancelWrite", "cancelClose").forEach {
            val destination = Destination(it)
            assertThrows<CancellationException> {
                ProtectedBackupPublication.publish(destination.file, ByteArray(20000))
            }
            assertTrue(destination.closed)
            assertTrue(destination.deleted)
            assertArrayEquals(byteArrayOf(10, 20, 30), destination.previous)
        }
    }

    @Test
    fun `actual coroutine cancellation during write cleans only new document`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val destination = Destination()
        every { destination.file.openOutputStream() } returns object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) {
                entered.complete(Unit)
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val task = async(Dispatchers.IO) { ProtectedBackupPublication.publish(destination.file, ByteArray(20000)) }
        try {
            entered.await()
            task.cancel()
        } finally {
            release.countDown()
        }
        assertThrows<CancellationException> { task.await() }
        task.join()
        assertTrue(destination.deleted)
        assertArrayEquals(byteArrayOf(10, 20, 30), destination.previous)
    }

    @Test
    fun `write allocation failure is controlled and attempts cleanup`() = runTest {
        val destination = Destination("oom")
        val error =
            assertThrows<BackupException> { ProtectedBackupPublication.publish(destination.file, byteArrayOf(1)) }
        assertEquals(BackupError.INSUFFICIENT_RESOURCES, error.error)
        assertTrue(destination.deleted)
    }

    @Test
    fun `serialized and compressed budgets reject overflow without external writes`() = runTest {
        val tooLarge = ByteArray(ConventionalBackupReader.MAX_DECOMPRESSED_BYTES.toInt() + 1)
        val error = assertThrows<BackupException> { ProtectedBackupPayload.compress(tooLarge) }
        assertEquals(BackupError.READ_LIMIT, error.error)
        val output = LimitedMemoryOutput(8)
        output.write(ByteArray(8))
        assertEquals(8, output.size())
        assertEquals(BackupError.READ_LIMIT, assertThrows<BackupException> { output.write(1) }.error)
        output.erase()
    }

    @Test
    fun `suppressed provider details are removed from fixed code errors`() = runTest {
        val source = BackupException(BackupError.IO).apply {
            addSuppressed(IOException("private title credential path"))
        }
        val error = assertThrows<BackupException> { protectedBackupOperation<Unit> { throw source } }
        assertEquals(BackupError.IO, error.error)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
    }

    @Test
    fun `protected names and diagnostics contain no content password or path`() = runTest {
        val name = BackupCreator.getProtectedFilename()
        assertTrue(name.matches(Regex("Pindorama_[a-f0-9-]{36}\\.pindobk")))
        assertFalse(name == BackupCreator.getProtectedFilename())
        val error = assertThrows<BackupException> {
            protectedBackupOperation<Unit> { throw IOException("private title password https://secret.example/path") }
        }
        assertEquals("BACKUP_CREATE: IO", backupDiagnostic(BackupOperation.CREATE, error))
        assertNull(error.cause)
    }

    private class Destination(private val failure: String = "") {
        val file = mockk<UniFile>()
        val bytes = ByteArrayOutputStream()
        val previous = byteArrayOf(10, 20, 30)
        var closed = false
        var deleted = false
        private var reads = 0

        init {
            every { file.isFile } returns true
            every { file.length() } returns 0L
            every { file.delete() } answers {
                deleted = true
                if (failure == "cleanup") throw IOException("private provider path")
                true
            }
            every { file.openInputStream() } answers {
                if (reads++ == 0) {
                    ByteArrayInputStream(bytes.toByteArray())
                } else {
                    check(closed)
                    if (failure == "read") throw IOException("private provider path")
                    var content = bytes.toByteArray()
                    when (failure) {
                        "corrupt" -> content[0] = (content[0].toInt() xor 1).toByte()
                        "truncated" -> content = content.copyOf(content.size - 1)
                        "extra" -> content += byteArrayOf(0)
                    }
                    ByteArrayInputStream(content)
                }
            }
            every { file.openOutputStream() } returns object : OutputStream() {
                override fun write(b: Int) = bytes.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    when (failure) {
                        "write", "cleanup" -> throw IOException("sensitive destination name")
                        "cancelWrite" -> throw CancellationException("sensitive destination name")
                        "oom" -> throw OutOfMemoryError("sensitive data")
                    }
                    bytes.write(b, off, len)
                }
                override fun close() {
                    closed = true
                    if (failure == "close") throw IOException("sensitive close")
                    if (failure == "cancelClose") throw CancellationException("sensitive cancellation")
                }
            }
        }
    }
}
