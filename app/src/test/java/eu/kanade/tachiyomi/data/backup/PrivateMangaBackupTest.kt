package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.creators.toBackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

@OptIn(ExperimentalSerializationApi::class)
class PrivateMangaBackupTest {
    @Test
    fun `private flag survives actual backup conversion and protobuf round trip`() {
        val manga = Manga.create().copy(source = 1, url = "/book", title = "Title", favorite = true, isPrivate = true)
        val encoded = ProtoBuf.encodeToByteArray(manga.toBackupManga())
        val restored = ProtoBuf.decodeFromByteArray<BackupManga>(encoded).getMangaImpl()
        assertTrue(restored.isPrivate)
        assertTrue(restored.favorite)
        assertEquals(manga.title, restored.title)
        assertEquals(manga.url, restored.url)
    }

    @Test
    fun `backup without optional field defaults to public`() {
        val encoded = ProtoBuf.encodeToByteArray(BackupManga(source = 1, url = "/book"))
        assertFalse(ProtoBuf.decodeFromByteArray<BackupManga>(encoded).getMangaImpl().isPrivate)
    }
}
