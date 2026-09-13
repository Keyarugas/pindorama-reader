package eu.kanade.tachiyomi.ui.security

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/** Keeps navigation requests across the authentication window and activity recreation. */
class PendingPrivacyIntents(private val savedState: SavedStateHandle) : ViewModel() {
    val intents = savedState.getStateFlow(KEY, arrayListOf<Intent>())

    fun add(intent: Intent) {
        savedState[KEY] = ArrayList(intents.value + Intent(intent))
    }

    fun removeFirst() {
        savedState[KEY] = ArrayList(intents.value.drop(1))
    }

    private companion object {
        const val KEY = "privacy_pending_intents"
    }
}
