package eu.kanade.tachiyomi.data.backup

import android.net.Uri
import androidx.lifecycle.viewModelScope
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupViewModel
import eu.kanade.presentation.more.settings.screen.data.VisualPreferenceImport
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.PreparedProtectedRestore
import eu.kanade.tachiyomi.data.backup.restore.ProtectedBackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.ProtectedRestoreLoader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtectedRestoreViewModelTest {
    @Test
    fun `protected visual preferences are opt in and results are independent`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Uri::class)
        val uri = mockk<Uri>()
        every { Uri.parse(any()) } returns uri
        try {
            for ((selected, fail, expected) in listOf(
                Triple(false, false, VisualPreferenceImport.NOT_SELECTED),
                Triple(true, false, VisualPreferenceImport.COMPLETE),
                Triple(true, true, VisualPreferenceImport.FAILED),
            )) {
                val loader = mockk<ProtectedRestoreLoader>()
                val restorer = mockk<ProtectedBackupRestorer>()
                val prepared = PreparedProtectedRestore.validated(Backup(emptyList()))
                coEvery { loader.isProtected(uri) } returns true
                coEvery { loader.prepare(uri, any()) } returns prepared
                coEvery { restorer.restore(prepared, any()) } returns Unit
                if (fail) {
                    coEvery { restorer.importVisualPreferences(prepared) } throws BackupException(BackupError.IO)
                } else {
                    coEvery { restorer.importVisualPreferences(prepared) } returns Unit
                }
                val model = RestoreBackupViewModel("content://synthetic", mockk(), mockk(), loader, restorer)
                try {
                    model.state.first { it.needsPassword }
                    model.unlock("synthetic".toCharArray())
                    model.state.first { it.canRestore && !it.working }
                    assertFalse(model.state.value.options.appSettings)
                    model.toggle({ options, enabled -> options.copy(appSettings = enabled) }, selected)
                    model.startRestore()
                    model.state.first { !it.working }
                    assertTrue(model.state.value.libraryComplete)
                    assertEquals(expected, model.state.value.visualPreferences)
                    assertEquals(!fail, model.state.value.complete)
                    coVerify(exactly = 1) { restorer.restore(prepared, match { !it.appSettings }) }
                    coVerify(exactly = if (selected) 1 else 0) { restorer.importVisualPreferences(prepared) }
                } finally {
                    model.viewModelScope.cancel()
                }
            }
        } finally {
            unmockkStatic(Uri::class)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `password failure never exposes options or starts application`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Uri::class)
        val uri = mockk<Uri>()
        every { Uri.parse(any()) } returns uri
        val loader = mockk<ProtectedRestoreLoader>()
        val restorer = mockk<ProtectedBackupRestorer>()
        coEvery { loader.isProtected(uri) } returns true
        coEvery { loader.prepare(uri, any()) } throws BackupException(BackupError.AUTHENTICATION_FAILED)
        val model = RestoreBackupViewModel("content://synthetic", mockk(), mockk(), loader, restorer)
        try {
            model.state.first { it.needsPassword }
            assertFalse(model.state.value.canRestore)
            val password = "synthetic secret".toCharArray()
            model.unlock(password)
            model.state.first { !it.working }
            assertFalse(model.state.value.canRestore)
            assertEquals(BackupError.AUTHENTICATION_FAILED, model.state.value.failure)
            assertTrue(model.state.value.needsPassword)
            assertFalse(model.startRestore())
            assertTrue(password.all { it == '\u0000' })
            assertFalse(model.state.value.toString().contains("synthetic"))
            coVerify(exactly = 0) { restorer.restore(any(), any()) }
        } finally {
            model.viewModelScope.cancel()
            unmockkStatic(Uri::class)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `cancellation before unlock coroutine starts clears secret and busy state`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Uri::class)
        val uri = mockk<Uri>()
        every { Uri.parse(any()) } returns uri
        val loader = mockk<ProtectedRestoreLoader>()
        coEvery { loader.isProtected(uri) } returns true
        val model = RestoreBackupViewModel("content://synthetic", mockk(), mockk(), loader, mockk())
        try {
            model.state.first { it.needsPassword }
            model.viewModelScope.cancel()
            val password = "synthetic".toCharArray()
            model.unlock(password)
            model.state.first { !it.working }
            assertFalse(model.state.value.canRestore)
            assertTrue(password.all { it == '\u0000' })
            coVerify(exactly = 0) { loader.prepare(any(), any()) }
            assertEquals(BackupError.CANCELLED, model.state.value.failure)
        } finally {
            unmockkStatic(Uri::class)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `selected format routes protected files to password and conventional files to validator`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Uri::class)
        val uri = mockk<Uri>()
        every { Uri.parse(any()) } returns uri
        try {
            for (protected in listOf(true, false)) {
                val loader = mockk<ProtectedRestoreLoader>()
                val validator = mockk<BackupFileValidator>()
                val restorer = mockk<ProtectedBackupRestorer>()
                coEvery { loader.isProtected(uri) } returns protected
                coEvery { validator.validate(uri) } returns BackupFileValidator.Results(emptyList(), emptyList())
                val model = RestoreBackupViewModel("content://synthetic", validator, mockk(), loader, restorer)
                try {
                    model.state.first { it.needsPassword || it.canRestore }
                    assertEquals(protected, model.state.value.protectedBackup)
                    assertEquals(protected, model.state.value.needsPassword)
                    assertEquals(!protected, model.state.value.canRestore)
                    coVerify(exactly = if (protected) 0 else 1) { validator.validate(uri) }
                    coVerify(exactly = 0) { loader.prepare(any(), any()) }
                    coVerify(exactly = 0) { restorer.restore(any(), any()) }
                } finally {
                    model.viewModelScope.cancel()
                }
            }
        } finally {
            unmockkStatic(Uri::class)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `busy restore reports contention without partial success and can retry authenticated data`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Uri::class)
        val uri = mockk<Uri>()
        every { Uri.parse(any()) } returns uri
        val loader = mockk<ProtectedRestoreLoader>()
        val restorer = mockk<ProtectedBackupRestorer>()
        val prepared = PreparedProtectedRestore.validated(Backup(emptyList()))
        coEvery { loader.isProtected(uri) } returns true
        coEvery { loader.prepare(uri, any()) } returns prepared
        coEvery { restorer.restore(prepared, any()) } throws BackupException(BackupError.BUSY)
        val model = RestoreBackupViewModel("content://synthetic", mockk(), mockk(), loader, restorer)
        try {
            model.state.first { it.needsPassword }
            model.unlock("synthetic".toCharArray())
            model.state.first { it.canRestore && !it.working }
            assertFalse(model.startRestore()) // Protected application stays on this screen.
            model.state.first { !it.working }
            assertEquals(BackupError.BUSY, model.state.value.failure)
            assertFalse(model.state.value.applicationStarted)
            assertFalse(model.state.value.libraryComplete)
            assertFalse(model.state.value.complete)
            assertTrue(model.state.value.canRestore)
            coEvery { restorer.restore(prepared, any()) } returns Unit
            model.startRestore()
            model.state.first { it.complete && !it.working }
            assertEquals(null, model.state.value.failure)
            coVerify(exactly = 1) { loader.prepare(uri, any()) }
            coVerify(exactly = 2) { restorer.restore(prepared, any()) }
            coVerify(exactly = 0) { restorer.importVisualPreferences(any()) }
        } finally {
            model.viewModelScope.cancel()
            unmockkStatic(Uri::class)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `invalid protected format and corrupt payload retain specific errors without application`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Uri::class)
        val uri = mockk<Uri>()
        every { Uri.parse(any()) } returns uri
        try {
            for (error in listOf(BackupError.PROTECTED_FORMAT, BackupError.INVALID_FILE)) {
                val loader = mockk<ProtectedRestoreLoader>()
                val restorer = mockk<ProtectedBackupRestorer>()
                if (error == BackupError.PROTECTED_FORMAT) {
                    coEvery { loader.isProtected(uri) } throws BackupException(error)
                } else {
                    coEvery { loader.isProtected(uri) } returns true
                    coEvery { loader.prepare(uri, any()) } throws BackupException(error)
                }
                val model = RestoreBackupViewModel("content://synthetic", mockk(), mockk(), loader, restorer)
                try {
                    model.state.first { it.needsPassword || it.failure != null }
                    if (model.state.value.needsPassword) model.unlock("synthetic".toCharArray())
                    model.state.first { it.failure != null && !it.working }
                    assertEquals(error, model.state.value.failure)
                    assertFalse(model.state.value.canRestore)
                    assertFalse(model.startRestore())
                    coVerify(exactly = 0) { restorer.restore(any(), any()) }
                } finally {
                    model.viewModelScope.cancel()
                }
            }
        } finally {
            unmockkStatic(Uri::class)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `cancellation during protected application is cancelled feedback rather than generic failure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkStatic(Uri::class)
        val uri = mockk<Uri>()
        every { Uri.parse(any()) } returns uri
        val loader = mockk<ProtectedRestoreLoader>()
        val restorer = mockk<ProtectedBackupRestorer>()
        val prepared = PreparedProtectedRestore.validated(Backup(emptyList()))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { loader.isProtected(uri) } returns true
        coEvery { loader.prepare(uri, any()) } returns prepared
        coEvery { restorer.restore(prepared, any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val model = RestoreBackupViewModel("content://synthetic", mockk(), mockk(), loader, restorer)
        try {
            model.state.first { it.needsPassword }
            model.unlock("synthetic".toCharArray())
            model.state.first { it.canRestore && !it.working }
            model.startRestore()
            entered.await()
            model.cancel()
            model.state.first { !it.working }
            assertEquals(BackupError.CANCELLED, model.state.value.failure)
            assertFalse(model.state.value.complete)
            assertFalse(model.state.value.libraryComplete)
            coVerify(exactly = 0) { restorer.importVisualPreferences(any()) }
        } finally {
            model.viewModelScope.cancel()
            unmockkStatic(Uri::class)
            Dispatchers.resetMain()
        }
    }
}
