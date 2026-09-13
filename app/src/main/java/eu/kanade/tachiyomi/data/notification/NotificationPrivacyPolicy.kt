package eu.kanade.tachiyomi.data.notification

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.core.security.NotificationPrivacyLevel
import tachiyomi.i18n.MR

/** No original title, chapter, filename, source or error message is interpolated into protected text. */
object NotificationPrivacyPolicy {
    enum class Event {
        UPDATES,
        LIBRARY_PROGRESS,
        DOWNLOAD_PROGRESS,
        DOWNLOAD_PAUSED,
        BACKUP_PROGRESS,
        BACKUP_COMPLETE,
        RESTORE_PROGRESS,
        RESTORE_COMPLETE,
        SYNC_PROGRESS,
        SYNC_COMPLETE,
        EXTENSIONS,
        IMAGE_SAVED,
        ERROR,
        UNKNOWN,
    }

    data class Content(
        val title: String,
        val text: String?,
        val subText: String? = null,
        val expandedLines: List<String> = emptyList(),
        val number: Int = 0,
    )

    fun transform(
        level: NotificationPrivacyLevel,
        event: Event,
        original: Content,
        appName: String,
        resolve: (StringResource) -> String,
    ): Content {
        if (level == NotificationPrivacyLevel.NORMAL) return original
        return Content(appName, resolve(message(level, event)))
    }

    private fun message(level: NotificationPrivacyLevel, event: Event): StringResource {
        if (event == Event.ERROR) return MR.strings.pindorama_notification_attention
        if (level == NotificationPrivacyLevel.PRIVATE) {
            return when (event) {
                Event.BACKUP_COMPLETE, Event.RESTORE_COMPLETE, Event.SYNC_COMPLETE, Event.IMAGE_SAVED ->
                    MR.strings.pindorama_notification_complete
                Event.LIBRARY_PROGRESS, Event.DOWNLOAD_PROGRESS, Event.BACKUP_PROGRESS,
                Event.RESTORE_PROGRESS, Event.SYNC_PROGRESS,
                ->
                    MR.strings.pindorama_notification_processing
                else -> MR.strings.pindorama_notification_activity
            }
        }
        return when (event) {
            Event.UPDATES -> MR.strings.pindorama_notification_updates
            Event.LIBRARY_PROGRESS -> MR.strings.pindorama_notification_library
            Event.DOWNLOAD_PROGRESS -> MR.strings.pindorama_notification_downloading
            Event.DOWNLOAD_PAUSED -> MR.strings.pindorama_notification_paused
            Event.BACKUP_PROGRESS -> MR.strings.pindorama_notification_backup_progress
            Event.BACKUP_COMPLETE -> MR.strings.pindorama_notification_backup_complete
            Event.RESTORE_PROGRESS -> MR.strings.pindorama_notification_restore_progress
            Event.RESTORE_COMPLETE -> MR.strings.pindorama_notification_restore_complete
            Event.SYNC_PROGRESS -> MR.strings.pindorama_notification_sync_progress
            Event.SYNC_COMPLETE -> MR.strings.pindorama_notification_sync_complete
            Event.EXTENSIONS -> MR.strings.pindorama_notification_extensions
            Event.IMAGE_SAVED -> MR.strings.pindorama_notification_image_saved
            else -> MR.strings.pindorama_notification_activity
        }
    }
}
