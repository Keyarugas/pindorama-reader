package eu.kanade.domain.ui

import android.content.SharedPreferences
import eu.kanade.domain.ui.model.AppTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.AndroidPreferenceStore

class UiPreferencesTest {

    @Test
    fun `missing theme uses Pindorama without saving a preference`() {
        val storage = mockk<SharedPreferences>()
        every { storage.getString("pref_app_theme", null) } returns null
        every { storage.contains("pref_app_theme") } returns false
        val preferences = UiPreferences(AndroidPreferenceStore(storage))

        assertEquals(AppTheme.PINDORAMA, preferences.appTheme.get())
        assertFalse(preferences.appTheme.isSet())
        verify(exactly = 0) { storage.edit() }
    }

    @Test
    fun `every existing theme choice is preserved`() {
        AppTheme.entries.forEach { theme ->
            val storage = mockk<SharedPreferences>()
            every { storage.getString("pref_app_theme", null) } returns theme.name
            every { storage.contains("pref_app_theme") } returns true
            val preferences = UiPreferences(AndroidPreferenceStore(storage))

            assertEquals(theme, preferences.appTheme.get())
            assertTrue(preferences.appTheme.isSet())
            verify(exactly = 0) { storage.edit() }
        }
    }
}
