package eu.kanade.tachiyomi.data.backup.restore

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.BackupError
import eu.kanade.tachiyomi.data.backup.BackupException
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.core.common.preference.PreferenceStore

/** Deny by default, including unknown future keys. No source preferences or scheduling side effects. */
@Inject
class ProtectedRestorePreferences(private val store: PreferenceStore) {
    internal suspend fun apply(preferences: List<BackupPreference>) {
        validate(preferences)
        preferences.filter { it.key in allowedBooleans }.forEach {
            currentCoroutineContext().ensureActive()
            store.getBoolean(it.key).set((it.value as BooleanPreferenceValue).value)
        }
        currentCoroutineContext().ensureActive()
        // Scheduling and security keys never change: do not call setupTask or launch network operations.
    }

    companion object {
        internal fun validate(preferences: List<BackupPreference>) {
            val seen = mutableSetOf<String>()
            preferences.filter { it.key in allowedBooleans }.forEach {
                if (!seen.add(it.key) || it.value !is BooleanPreferenceValue) {
                    throw BackupException(BackupError.INVALID_FILE)
                }
            }
        }

        internal val allowedBooleans = setOf(
            "pref_theme_dark_amoled_key",
            "relative_time_v2",
            "display_download_badge",
            "display_unread_badge",
            "display_local_badge",
            "display_language_badge",
            "display_category_tabs",
            "display_number_of_items",
            "display_continue_reading_button",
        )
    }
}
