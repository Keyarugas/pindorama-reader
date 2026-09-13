package eu.kanade.tachiyomi.data.notification

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.NotificationPrivacyLevel
import eu.kanade.tachiyomi.data.notification.NotificationPrivacyPolicy.Event
import eu.kanade.tachiyomi.util.system.notificationManager
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/** Rebuild presentation from a whitelist; never carry pictures, custom views or arbitrary extras across. */
fun Context.applyNotificationPrivacy(
    original: Notification,
    level: NotificationPrivacyLevel = appGraph.securityPreferences.notificationPrivacyLevel.get(),
): Notification {
    if (level == NotificationPrivacyLevel.NORMAL) return original
    val event = original.extras?.getString(PRIVACY_EVENT)?.let { name -> Event.entries.find { it.name == name } }
        ?: notificationEvent(original)
    val content = NotificationPrivacyPolicy.transform(
        level,
        event,
        NotificationPrivacyPolicy.Content("", null),
        stringResource(MR.strings.app_name),
        ::stringResource,
    )
    val builder = NotificationCompat.Builder(this, original.channelId ?: Notifications.CHANNEL_COMMON)
        .setSmallIcon(R.drawable.ic_pindorama)
        .setContentTitle(content.title)
        .setContentText(content.text)
        .setContentIntent(original.contentIntent)
        .setDeleteIntent(original.deleteIntent)
        .setOngoing(original.flags and Notification.FLAG_ONGOING_EVENT != 0)
        .setAutoCancel(original.flags and Notification.FLAG_AUTO_CANCEL != 0)
        .setOnlyAlertOnce(original.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        .setGroup(original.group?.takeIf { it == Notifications.GROUP_NEW_CHAPTERS })
        .setGroupSummary(original.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        .setGroupAlertBehavior(NotificationCompat.getGroupAlertBehavior(original))
        .setPriority(original.priority)
        .setTimeoutAfter(original.timeoutAfter)
        .setShowWhen(false)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setAllowSystemGeneratedContextualActions(false)
    // Keep working controls and their PendingIntents. Never copy potentially identifying action labels/extras.
    for (index in 0 until NotificationCompat.getActionCount(original)) {
        val action = NotificationCompat.getAction(original, index) ?: continue
        builder.addAction(
            NotificationCompat.Action.Builder(
                0,
                neutralActionTitle(action.title),
                action.actionIntent,
            ).setSemanticAction(action.semanticAction).setAllowGeneratedReplies(false).build(),
        )
    }
    builder.extras.putString(PRIVACY_EVENT, event.name)
    return builder.build().apply {
        flags = flags or (original.flags and Notification.FLAG_FOREGROUND_SERVICE)
    }
}

/** Called when the user chooses a level so already-posted content is redacted too. */
fun Context.refreshNotificationPrivacy(level: NotificationPrivacyLevel) {
    if (level == NotificationPrivacyLevel.NORMAL) return // Detailed content returns on the next task update.
    notificationManager.activeNotifications.forEach {
        val protected = applyNotificationPrivacy(it.notification, level).apply {
            flags = flags or Notification.FLAG_ONLY_ALERT_ONCE
        }
        NotificationManagerCompat.from(this).notify(it.tag, it.id, protected)
    }
}

private fun Context.neutralActionTitle(title: CharSequence?): String {
    val safeLabels = listOf(
        MR.strings.action_pause,
        MR.strings.action_resume,
        MR.strings.action_cancel,
        MR.strings.action_cancel_all,
        MR.strings.action_share,
        MR.strings.action_mark_as_read,
        MR.strings.action_download,
    ).map { stringResource(it) }
    return safeLabels.firstOrNull { it == title?.toString() }
        ?: stringResource(MR.strings.pindorama_notification_open)
}

private fun Context.notificationEvent(notification: Notification): Event {
    val title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    val errors = listOf(
        MR.strings.creating_backup_error,
        MR.strings.restoring_backup_error,
        MR.strings.download_notifier_title_error,
        MR.strings.label_warning,
    ).map { stringResource(it) }
    if (title in errors) return Event.ERROR
    val hasErrors = (0 until NotificationCompat.getActionCount(notification)).any {
        NotificationCompat.getAction(notification, it)?.title?.toString() ==
            stringResource(MR.strings.action_show_errors)
    }
    if (hasErrors) return Event.ERROR
    return when (notification.channelId) {
        Notifications.CHANNEL_NEW_CHAPTERS -> Event.UPDATES
        Notifications.CHANNEL_LIBRARY_ERROR, Notifications.CHANNEL_DOWNLOADER_ERROR -> Event.ERROR
        Notifications.CHANNEL_LIBRARY_PROGRESS -> Event.LIBRARY_PROGRESS
        Notifications.CHANNEL_DOWNLOADER_PROGRESS -> {
            if (title == stringResource(MR.strings.chapter_paused)) Event.DOWNLOAD_PAUSED else Event.DOWNLOAD_PROGRESS
        }
        Notifications.CHANNEL_BACKUP_RESTORE_PROGRESS -> when (title) {
            stringResource(MR.strings.syncing_library) -> Event.SYNC_PROGRESS
            stringResource(MR.strings.restoring_backup) -> Event.RESTORE_PROGRESS
            else -> Event.BACKUP_PROGRESS
        }
        Notifications.CHANNEL_BACKUP_RESTORE_COMPLETE -> when (title) {
            stringResource(MR.strings.library_sync_complete) -> Event.SYNC_COMPLETE
            stringResource(MR.strings.restore_completed) -> Event.RESTORE_COMPLETE
            else -> Event.BACKUP_COMPLETE
        }
        Notifications.CHANNEL_EXTENSIONS_UPDATE -> Event.EXTENSIONS
        Notifications.CHANNEL_COMMON -> {
            if (title == stringResource(MR.strings.picture_saved)) Event.IMAGE_SAVED else Event.UNKNOWN
        }
        else -> Event.UNKNOWN
    }
}

private const val PRIVACY_EVENT = "pindorama.notification.privacy.event"
