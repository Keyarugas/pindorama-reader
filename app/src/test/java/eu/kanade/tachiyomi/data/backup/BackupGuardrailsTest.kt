package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.core.security.PrivateContentSession
import eu.kanade.tachiyomi.core.security.PrivateContentSessionState
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.ConventionalBackupPolicy
import eu.kanade.tachiyomi.data.backup.create.publishBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.manga.model.Manga
import java.io.IOException

class BackupGuardrailsTest {
    private val privateManga = Manga.create().copy(id = 1, title = "Sensitive title", isPrivate = true)

    @Test
    fun `only included works block export and no flags are modified`() {
        ConventionalBackupPolicy.check(listOf(privateManga.copy(isPrivate = false)), BackupOptions())
        ConventionalBackupPolicy.check(listOf(privateManga), BackupOptions(libraryEntries = false))
        val error = assertThrows<BackupException> {
            ConventionalBackupPolicy.check(listOf(privateManga), BackupOptions())
        }
        assertEquals(BackupError.PROTECTION_REQUIRED, error.error)
        assertTrue(privateManga.isPrivate)
        assertFalse(error.toString().contains(privateManga.title))
    }

    @Test
    fun `unlocked session does not authorize conventional export`() {
        val session = PrivateContentSession()
        session.authenticationSucceeded(session.beginAuthentication()!!)
        assertEquals(PrivateContentSessionState.UNLOCKED, session.state.value)
        assertThrows<BackupException> { ConventionalBackupPolicy.check(listOf(privateManga), BackupOptions()) }
    }

    @Test
    fun `diagnostics discard all attacker supplied text including exception causes`() {
        val secret = "Sensitive title https://user:password@host/private?token=abc /storage/private.tachibk Repo secret"
        listOf(IOException(secret), IllegalStateException(secret, IOException(secret)), SecurityException(secret))
            .forEach { exception ->
                BackupOperation.entries.forEach { operation ->
                    val diagnostic = backupDiagnostic(operation, exception)
                    assertEquals("BACKUP_${operation.name}: ${BackupError.from(exception).name}", diagnostic)
                    listOf("Sensitive", "https", "password", "token", "storage", "Repo").forEach {
                        assertFalse(diagnostic.contains(it))
                    }
                }
            }
    }

    @Test
    fun `write validation failure and cancellation preserve all previous copies`() = runTest {
        listOf("write", "validate").forEach { stage ->
            listOf(IOException("sensitive"), CancellationException("sensitive")).forEach { failure ->
                val copies = mutableListOf("old1", "old2", "new")
                var retained = false
                assertThrows<Exception> {
                    publishBackup(
                        write = { if (stage == "write") throw failure },
                        validate = { if (stage == "validate") throw failure },
                        cleanup = { copies.remove("new") },
                        retain = {
                            retained = true
                            copies.remove("old1")
                        },
                    )
                }
                assertEquals(listOf("old1", "old2"), copies)
                assertFalse(retained)
            }
        }
    }

    @Test
    fun `retention runs only after validation and cannot delete verified output on failure`() = runTest {
        val stages = mutableListOf<String>()
        assertThrows<IOException> {
            publishBackup(
                write = { stages.add("write and close") },
                validate = { stages.add("validate") },
                cleanup = { stages.add("delete new") },
                retain = {
                    stages.add("retain")
                    throw IOException()
                },
            )
        }
        assertEquals(listOf("write and close", "validate", "retain"), stages)
    }
}
