package eu.kanade.tachiyomi.data.backup

import android.content.SharedPreferences
import app.cash.sqldelight.SuspendingTransactionWithoutReturn
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.core.security.PrivateContentSessionState
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.backup.create.ProtectedBackupPayload
import eu.kanade.tachiyomi.data.backup.crypto.BackupCrypto
import eu.kanade.tachiyomi.data.backup.crypto.BackupCryptoError
import eu.kanade.tachiyomi.data.backup.crypto.BackupCryptoException
import eu.kanade.tachiyomi.data.backup.crypto.CryptoMemoryBudget
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.PreparedProtectedRestore
import eu.kanade.tachiyomi.data.backup.restore.ProtectedBackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.ProtectedRestoreLoader
import eu.kanade.tachiyomi.data.backup.restore.ProtectedRestorePreferences
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.ui.security.PrivateContentSessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.LibraryOperationCoordinator
import tachiyomi.data.Database
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer

class ProtectedBackupRestoreTest {
    @Test
    fun `restore without library selections bypasses active participant and preserves generation and data`() = runTest {
        val target = Target()
        val coordinator = LibraryOperationCoordinator.shared
        val generation = coordinator.generation
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val participant = launch {
            coordinator.operation {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        try {
            val prepared = PreparedProtectedRestore.validated(backup())
            target.restorer.restore(
                prepared,
                RestoreOptions(categories = false, libraryEntries = false, appSettings = false),
            )
            assertTrue(participant.isActive)
            assertEquals(generation, coordinator.generation)
            target.untouched()
            coVerify(exactly = 0) { target.database.transaction(any(), any()) }
            coVerify(exactly = 0) { target.manga.restoreProtected(any(), any()) }
            target.restorer.importVisualPreferences(prepared)
            assertFalse(target.store.getBoolean("display_unread_badge").get())
            assertTrue(target.store.getBoolean("display_unread_badge").isSet())
            assertTrue(target.entries.isEmpty())
            assertEquals(generation, coordinator.generation)
            assertTrue(participant.isActive)
        } finally {
            participant.cancelAndJoin()
        }
    }

    @Test
    fun `library phase preserves preferences until explicit separate import`() = runTest {
        val target = Target()
        target.store.getBoolean("display_unread_badge").set(true)
        val prepared = PreparedProtectedRestore.validated(backup())
        target.restorer.restore(prepared, RestoreOptions(appSettings = false))
        assertTrue(target.entries.single().isPrivate)
        assertTrue(target.store.getBoolean("display_unread_badge").get())
        target.restorer.importVisualPreferences(prepared)
        assertFalse(target.store.getBoolean("display_unread_badge").get())
        assertEquals(1, target.entries.size)
    }

    private val password get() = "Senha sintética 🔒\u0000 e\u0301".toCharArray()
    private fun fixture() = checkNotNull(
        javaClass.getResourceAsStream("/backup-protected-create/private-gzip-v1.pindobk"),
    ).use { it.readBytes() }
    private suspend fun encrypted(backup: Backup) = BackupCrypto().encrypt(
        ProtectedBackupPayload.encode(backup, ProtoBuf),
        password,
    )
    private fun backup() = Backup(listOf(BackupManga(1, "/synthetic", "private synthetic", isPrivate = true))).apply {
        backupPreferences = listOf(BackupPreference("display_unread_badge", BooleanPreferenceValue(false)))
    }

    private class Target(val store: PreferenceStore = testPreferenceStore()) {
        val entries = mutableListOf<BackupManga>()
        val categories = mockk<CategoriesRestorer>(relaxed = true)
        val manga = mockk<MangaRestorer>()
        val preferences = ProtectedRestorePreferences(store)
        val database = mockk<Database>()
        val restorer = ProtectedBackupRestorer(
            categories,
            manga,
            preferences,
            database,
            mockk<DelayedTrackingStore> { every { getItems() } returns emptyList() },
        )
        init {
            // Control-flow tests only. Actual rollback is verified with SQLite in ProtectedRestoreDatabaseTest.
            coEvery { database.transaction(any(), any()) } coAnswers {
                secondArg<suspend SuspendingTransactionWithoutReturn.() -> Unit>().invoke(mockk(relaxed = true))
            }
            coEvery { manga.restoreProtected(any(), any()) } answers { entries += firstArg<BackupManga>() }
        }
        suspend fun restore(file: ByteArray, password: CharArray) {
            val prepared = ProtectedRestoreLoader.prepare(ByteArrayInputStream(file), password)
            restorer.restore(prepared, RestoreOptions())
        }
        fun untouched() {
            assertTrue(entries.isEmpty())
            assertFalse(store.getBoolean("display_unread_badge").isSet())
            coVerify(exactly = 0) { categories.restoreData(any()) }
        }
    }

    @Test
    fun `frozen fixture restores private data without opening session`() = runTest {
        val bytes = fixture()
        assertEquals(
            "031cc80f2a391e32491094ba1af6460ef3cfa258b005d12dba88e8d984a406d7",
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(),
        )
        PrivateContentSessionManager.session.lock()
        val target = Target()
        target.restore(bytes, password)
        assertTrue(target.entries.single().isPrivate)
        assertEquals(PrivateContentSessionState.LOCKED, PrivateContentSessionManager.session.state.value)
    }

    @Test
    fun `creation and independent restore preserve unicode and private data`() = runTest {
        val target = Target()
        target.restore(encrypted(backup()), password)
        assertEquals("private synthetic", target.entries.single().title)
        assertTrue(target.entries.single().isPrivate)
        assertTrue(target.store.getBoolean("display_unread_badge").isSet())
        assertFalse(target.store.getBoolean("display_unread_badge").get())
    }

    @Test
    fun `wrong password and authenticated envelope mutations never apply anything`() = runTest {
        val file = fixture()
        val variants = listOf(
            file.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() },
            file.copyOf().apply { this[65] = (this[65].toInt() xor 1).toByte() },
            file.copyOf().apply { this[40] = (this[40].toInt() xor 1).toByte() },
            file.copyOf().apply { this[9] = 2 },
            file.copyOf().apply { this[12] = 99 },
            file.copyOf().apply { this[0] = 0 },
            file.copyOf().apply { ByteBuffer.wrap(this).putLong(28, 128L * 1024 * 1024 + 1) },
            file.copyOf().apply { ByteBuffer.wrap(this).putInt(16, Int.MAX_VALUE) },
        ) + listOf(0, 7, 63, 64, file.size - 1).map { file.copyOf(it) }
        for (bytes in variants) {
            val target = Target()
            val error = assertThrows<BackupException> { target.restore(bytes, password) }
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            assertFalse(error.toString().contains("synthetic"))
            target.untouched()
        }
        val target = Target()
        assertEquals(
            BackupError.AUTHENTICATION_FAILED,
            assertThrows<BackupException> { target.restore(file, "incorrect".toCharArray()) }.error,
        )
        target.untouched()
    }

    @Test
    fun `recognition uses content and rejects reserved truncated unknown headers`() = runTest {
        assertTrue(ProtectedRestoreLoader.recognize(ByteArrayInputStream(fixture())))
        val conventional = ProtectedBackupPayload.encode(backup(), ProtoBuf)
        assertFalse(ProtectedRestoreLoader.recognize(ByteArrayInputStream(conventional)))
        for (file in listOf(fixture().copyOf(7), fixture().copyOf().apply { this[9] = 2 })) {
            assertEquals(
                BackupError.PROTECTED_FORMAT,
                assertThrows<BackupException> { ProtectedRestoreLoader.recognize(ByteArrayInputStream(file)) }.error,
            )
        }
        assertTrue(
            ConventionalBackupReader.decode(Buffer().write(conventional), ProtoBuf).backupManga.single().isPrivate,
        )
    }

    @Test
    fun `authenticated malformed protobuf and invalid memo fail before data application`() = runTest {
        val files = listOf(
            BackupCrypto().encrypt(byteArrayOf(0, 0, 0), password),
            encrypted(backup().apply { backupManga.single().memo = byteArrayOf(0) }),
            encrypted(
                backup().apply {
                    backupManga.single().chapters =
                        listOf(BackupChapter("/x", "x", chapterNumber = Float.NaN))
                },
            ),
            encrypted(
                backup().apply {
                    backupPreferences =
                        listOf(BackupPreference("display_unread_badge", StringPreferenceValue("bad")))
                },
            ),
        )
        files.forEach { file ->
            val target = Target()
            assertThrows<BackupException> { target.restore(file, password) }
            target.untouched()
        }
    }

    @Test
    fun `authenticated expansion beyond conventional budget is rejected without application`() = runTest {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(output).use { gzip ->
            val chunk = ByteArray(8192)
            repeat(8193) { gzip.write(chunk) }
        }
        val target = Target()
        assertEquals(
            BackupError.READ_LIMIT,
            assertThrows<BackupException> {
                target.restore(BackupCrypto().encrypt(output.toByteArray(), password), password)
            }.error,
        )
        target.untouched()
    }

    @Test
    fun `insufficient crypto resources cannot touch preferences or data`() = runTest {
        val target = Target()
        mockkObject(CryptoMemoryBudget.Companion)
        try {
            every { CryptoMemoryBudget.RUNTIME } returns CryptoMemoryBudget { _, _ ->
                throw BackupCryptoException(BackupCryptoError.INSUFFICIENT_RESOURCES)
            }
            assertEquals(
                BackupError.INSUFFICIENT_RESOURCES,
                assertThrows<BackupException> { target.restore(fixture(), password) }.error,
            )
            target.untouched()
        } finally {
            unmockkObject(CryptoMemoryBudget.Companion)
        }
    }

    @Test
    fun `cancel before authentication has no application effects and clears password`() = runTest {
        val target = Target()
        val secret = password
        val task = async {
            currentCoroutineContext().cancel()
            target.restore(fixture(), secret)
        }
        assertThrows<CancellationException> { task.await() }
        assertTrue(secret.all { it == '\u0000' })
        target.untouched()
    }

    @Test
    fun `security privacy credentials tasks source trust and unknown preferences stay local`() = runTest {
        val store = testPreferenceStore()
        val security = SecurityPreferences(store)
        security.useAuthenticator.set(true)
        security.lockAppAfter.set(5)
        security.secureScreen.set(SecurityPreferences.SecureScreenMode.ALWAYS)
        store.getString("notification_privacy_level").set("PRIVATE")
        store.getString("__PRIVATE_track_token_1").set("local synthetic token")
        val baseline = store.getAll().toMap()
        val prefs = listOf(
            BackupPreference("use_biometric_lock", BooleanPreferenceValue(false)),
            BackupPreference("lock_app_after", IntPreferenceValue(0)),
            BackupPreference("secure_screen_v2", StringPreferenceValue("NEVER")),
            BackupPreference("notification_privacy_level", StringPreferenceValue("NORMAL")),
            BackupPreference("__PRIVATE_track_token_1", StringPreferenceValue("imported synthetic token")),
            BackupPreference("backup_interval", IntPreferenceValue(1)),
            BackupPreference("pref_library_update_interval_key", IntPreferenceValue(1)),
            BackupPreference("download_new", BooleanPreferenceValue(true)),
            BackupPreference("pref_auto_update_manga_sync_key", BooleanPreferenceValue(true)),
            BackupPreference("show_nsfw_source", BooleanPreferenceValue(true)),
            BackupPreference("__APP_STATE_incognito_mode", BooleanPreferenceValue(false)),
            BackupPreference("future_unknown_security_key", BooleanPreferenceValue(false)),
        )
        ProtectedRestorePreferences(store).apply(prefs)
        assertEquals(baseline, store.getAll())
        assertTrue(security.useAuthenticator.get())
        assertEquals(SecurityPreferences.SecureScreenMode.ALWAYS, security.secureScreen.get())
    }

    @Test
    fun `simulated transaction verifies fail fast control flow before preferences`() = runTest {
        val target = Target()
        val data = backup().copy(backupManga = listOf(BackupManga(1, "/first"), BackupManga(1, "/second")))
        coEvery { target.manga.restoreProtected(match { it.url == "/second" }, any()) } throws
            IOException("private details")
        val error = assertThrows<BackupException> { target.restore(encrypted(data), password) }
        assertEquals("IO", error.message)
        assertEquals(listOf("/first"), target.entries.map { it.url })
        assertFalse(target.store.getBoolean("display_unread_badge").isSet())
    }

    @Test
    fun `cancellation during application prevents subsequent entries and preferences`() = runTest {
        val target = Target()
        coEvery { target.manga.restoreProtected(any(), any()) } coAnswers {
            target.entries += firstArg<BackupManga>()
            currentCoroutineContext().cancel()
        }
        val file = encrypted(backup())
        val task = async { target.restore(file, password) }
        assertThrows<CancellationException> { task.await() }
        assertEquals(1, target.entries.size)
        assertFalse(target.store.getBoolean("display_unread_badge").isSet())
    }

    @Test
    fun `cancel during preference phase removes previously absent visual choice`() = runTest {
        val backing = testPreferenceStore()
        val file = encrypted(
            backup().apply {
                backupPreferences = listOf(
                    BackupPreference("display_unread_badge", BooleanPreferenceValue(false)),
                    BackupPreference("display_local_badge", BooleanPreferenceValue(false)),
                )
            },
        )
        val task = async {
            val context = currentCoroutineContext()
            val store = object : PreferenceStore by backing {
                override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> {
                    val preference = backing.getBoolean(key, defaultValue)
                    return object : Preference<Boolean> by preference {
                        override fun set(value: Boolean) {
                            preference.set(value)
                            context.cancel()
                        }
                    }
                }
            }
            Target(store).restore(file, password)
        }
        assertThrows<CancellationException> { task.await() }
        assertFalse(backing.getBoolean("display_unread_badge").isSet())
        assertFalse(backing.getBoolean("display_local_badge").isSet())
    }

    @Test
    fun `preference failure restores previous value and removes new key`() = runTest {
        val backing = testPreferenceStore()
        backing.getBoolean("display_unread_badge").set(false)
        val store = object : PreferenceStore by backing {
            override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> {
                val preference = backing.getBoolean(key, defaultValue)
                return object : Preference<Boolean> by preference {
                    override fun set(value: Boolean) {
                        preference.set(value)
                        if (key == "display_local_badge") throw IOException("synthetic")
                    }
                }
            }
        }
        val data = backup().apply {
            backupPreferences = listOf(
                BackupPreference("display_unread_badge", BooleanPreferenceValue(true)),
                BackupPreference("display_local_badge", BooleanPreferenceValue(false)),
            )
        }
        assertThrows<BackupException> { Target(store).restore(encrypted(data), password) }
        assertFalse(backing.getBoolean("display_unread_badge").get())
        assertTrue(backing.getBoolean("display_unread_badge").isSet())
        assertFalse(backing.getBoolean("display_local_badge").isSet())
    }

    @Test
    fun `failed preference recovery exposes fixed code without hiding partial state`() = runTest {
        val backing = testPreferenceStore()
        backing.getBoolean("display_unread_badge").set(false)
        val store = object : PreferenceStore by backing {
            override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> {
                val preference = backing.getBoolean(key, defaultValue)
                return object : Preference<Boolean> by preference {
                    override fun set(value: Boolean) {
                        if (!value) throw IOException("private synthetic rollback failure")
                        preference.set(value)
                        throw IOException("private synthetic application failure")
                    }
                }
            }
        }
        val data = backup().apply {
            backupPreferences = listOf(BackupPreference("display_unread_badge", BooleanPreferenceValue(true)))
        }
        val error = assertThrows<BackupException> { Target(store).restore(encrypted(data), password) }
        assertEquals(BackupError.PREFERENCE_RECOVERY_FAILED, error.error)
        assertNull(error.cause)
        assertTrue(backing.getBoolean("display_unread_badge").get())
    }

    @Test
    fun `preference write failure is surfaced without claiming full restoration`() = runTest {
        val store = mockk<PreferenceStore>()
        every { store.getAll() } returns emptyMap<String, Any>()
        every { store.getBoolean(any(), any()).isSet() } returns false
        every { store.getBoolean(any(), any()).set(any()) } throws IOException("private preference details")
        val target = Target(store)
        val error = assertThrows<BackupException> { target.restore(encrypted(backup()), password) }
        assertEquals(BackupError.IO, error.error)
        assertEquals(1, target.entries.size)
        assertNull(error.cause)
    }
}

private fun testPreferenceStore(): PreferenceStore {
    val values = mutableMapOf<String, Any?>()
    val shared = mockk<SharedPreferences>(relaxed = true)
    val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    every { shared.all } answers { values.toMap() }
    every { shared.contains(any()) } answers { values.containsKey(firstArg()) }
    every { shared.edit() } returns editor
    every { shared.getBoolean(any(), any()) } answers { values[firstArg<String>()] as? Boolean ?: secondArg() }
    every { shared.getInt(any(), any()) } answers { values[firstArg<String>()] as? Int ?: secondArg() }
    every { shared.getString(any(), any()) } answers { values[firstArg<String>()] as? String ?: secondArg() }
    every { editor.remove(any()) } answers {
        values.remove(firstArg<String>())
        editor
    }
    every { editor.putBoolean(any(), any()) } answers {
        values[firstArg()] = secondArg<Boolean>()
        editor
    }
    every { editor.putInt(any(), any()) } answers {
        values[firstArg()] = secondArg<Int>()
        editor
    }
    every { editor.putString(any(), any()) } answers {
        values[firstArg()] = secondArg<String>()
        editor
    }
    return AndroidPreferenceStore(shared)
}
