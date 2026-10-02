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
        } finally {
            unmockkStatic(Uri::class)
            Dispatchers.resetMain()
        }
    }
}
