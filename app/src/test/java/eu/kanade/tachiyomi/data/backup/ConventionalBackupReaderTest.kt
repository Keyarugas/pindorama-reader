package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.Sink
import okio.buffer
import okio.gzip
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConventionalBackupReaderTest {
    private fun encode(backup: Backup) = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)

    private fun gzip(bytes: ByteArray): Buffer = Buffer().also { result ->
        (result as Sink).gzip().buffer().use { it.write(bytes) }
    }

    @Test
    fun `legacy protobuf fixture with no private field restores raw and gzipped`() = runTest {
        // Hand-encoded legacy Backup: manga { source: 1, url: "/book", title: "Title" }.
        // Independent of the current encoder and contains no Pindorama fields.
        val legacy = byteArrayOf(10, 16, 8, 1, 18, 5, 47, 98, 111, 111, 107, 26, 5, 84, 105, 116, 108, 101)
        listOf(Buffer().write(legacy), gzip(legacy)).forEach {
            val manga = ConventionalBackupReader.decode(it, ProtoBuf).backupManga.single()
            assertEquals("/book", manga.url)
            assertEquals("Title", manga.title)
            assertFalse(manga.isPrivate)
        }
    }

    @Test
    fun `existing private backup remains restorable without changing protobuf`() = runTest {
        val bytes = encode(Backup(listOf(BackupManga(1, "/private", isPrivate = true))))
        assertTrue(ConventionalBackupReader.decode(gzip(bytes), ProtoBuf).backupManga.single().isPrivate)
    }

    @Test
    fun `invalid json protobuf truncated gzip and bad checksum are rejected`() = runTest {
        val good = gzip(encode(Backup(listOf(BackupManga(1, "/book"))))).readByteArray()
        val corrupt = good.copyOf().also { it[it.lastIndex - 4] = (it[it.lastIndex - 4].toInt() xor 1).toByte() }
        listOf(
            byteArrayOf(),
            byteArrayOf(10),
            "{}".toByteArray(),
            byteArrayOf(10, 127, 1),
            good.dropLast(5).toByteArray(),
            corrupt,
        )
            .forEach {
                val error = assertThrows<BackupException> {
                    ConventionalBackupReader.decode(Buffer().write(it), ProtoBuf)
                }
                assertEquals(BackupError.INVALID_FILE, error.error)
            }
    }

    @Test
    fun `size limits accept exact boundary and reject one extra byte`() = runTest {
        val bytes = encode(Backup(listOf(BackupManga(1, "/book"))))
        ConventionalBackupReader.decode(Buffer().write(bytes), ProtoBuf, bytes.size.toLong(), bytes.size.toLong())
        val expanded = assertThrows<BackupException> {
            ConventionalBackupReader.decode(gzip(bytes), ProtoBuf, bytes.size.toLong() - 1)
        }
        val input = assertThrows<BackupException> {
            ConventionalBackupReader.decode(Buffer().write(bytes), ProtoBuf, maxInputBytes = bytes.size.toLong() - 1)
        }
        assertEquals(BackupError.READ_LIMIT, expanded.error)
        assertEquals(BackupError.READ_LIMIT, input.error)
    }

    @Test
    fun `gzip expansion past production budget stops before protobuf decoding`() = runTest {
        val compressed = Buffer()
        (compressed as Sink).gzip().buffer().use { sink ->
            val block = ByteArray(8192)
            repeat((ConventionalBackupReader.MAX_DECOMPRESSED_BYTES / block.size).toInt() + 1) {
                sink.write(block)
            }
        }
        val error = assertThrows<BackupException> { ConventionalBackupReader.decode(compressed, ProtoBuf) }
        assertEquals(BackupError.READ_LIMIT, error.error)
    }

    @Test
    fun `large synthetic library with two hundred thousand chapters remains supported`() = runTest {
        val backup = Backup(
            (1..10000).map { id ->
                BackupManga(
                    source = 1,
                    url = "/book/$id",
                    title = "Synthetic work $id",
                    description = "Synthetic description ".repeat(50),
                    chapters = (1..20).map { chapter ->
                        BackupChapter("/book/$id/chapter/$chapter", "Synthetic chapter $chapter", read = true)
                    },
                )
            },
        )
        val bytes = encode(backup)
        assertTrue(bytes.size > 10 * 1024 * 1024)
        assertTrue(bytes.size < ConventionalBackupReader.MAX_DECOMPRESSED_BYTES)
        val restored = ConventionalBackupReader.decode(gzip(bytes), ProtoBuf)
        assertEquals(10000, restored.backupManga.size)
        assertEquals(200000, restored.backupManga.sumOf { it.chapters.size })
    }
}
