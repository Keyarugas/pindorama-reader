package eu.kanade.tachiyomi.data.backup.restore

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.BackupError
import eu.kanade.tachiyomi.data.backup.BackupException
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.PreferenceStore

/** Deny by default, including unknown future keys. No source preferences or scheduling side effects. */
@Inject
class ProtectedRestorePreferences(private val store: PreferenceStore) {
    internal suspend fun apply(preferences: List<BackupPreference>) {
        validate(preferences)
        val selected = preferences.filter { it.key in allowedBooleans }
        val local = store.getAll()
        val before = selected.associate { preference ->
            val old = local[preference.key]
            if (old != null && old !is Boolean) throw BackupException(BackupError.INVALID_FILE)
            preference.key to (old as Boolean?)
        }
        val attempted = mutableListOf<BackupPreference>()
        try {
            selected.forEach {
                currentCoroutineContext().ensureActive()
                attempted += it // Include writes that modify state and then throw.
                store.getBoolean(it.key).set((it.value as BooleanPreferenceValue).value)
            }
            currentCoroutineContext().ensureActive()
        } catch (error: Exception) {
            val recovered = withContext(NonCancellable) {
                var success = true
                attempted.asReversed().forEach {
                    try {
                        val preference = store.getBoolean(it.key)
                        val imported = (it.value as BooleanPreferenceValue).value
                        // Avoid overwriting an observable newer local choice. This is not an atomic CAS.
                        if (preference.isSet() && preference.get() == imported) {
                            val old = before[it.key]
                            if (old == null) preference.delete() else preference.set(old)
                        }
                    } catch (_: Exception) {
                        success = false
                    }
                }
                success
            }
            if (!recovered) throw BackupException(BackupError.PREFERENCE_RECOVERY_FAILED)
            throw error
        }
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
