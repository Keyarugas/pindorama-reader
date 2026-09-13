package eu.kanade.tachiyomi.data.notification

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.core.security.NotificationPrivacyLevel
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationPrivacyPolicy.Content
import eu.kanade.tachiyomi.data.notification.NotificationPrivacyPolicy.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.i18n.MR

class NotificationPrivacyPolicyTest {
    private val sensitive = Content(
        title = "Obra reservada — Capítulo 42",
        text = "Fonte secreta /storage/emulated/0/backup-pessoal.tachibk",
        subText = "Nome privado",
        expandedLines = listOf("Obra reservada", "Capítulo 42"),
        number = 42,
    )

    @Test
    fun `normal preserves the exact existing content`() {
        Event.entries.forEach { assertSame(sensitive, transform(NotificationPrivacyLevel.NORMAL, it)) }
    }

    @Test
    fun `existing users default to normal without changing legacy preference`() {
        val preferences = SecurityPreferences(InMemoryPreferenceStore())
        preferences.hideNotificationContent.set(true)
        assertEquals(NotificationPrivacyLevel.NORMAL, preferences.notificationPrivacyLevel.get())
        assertTrue(preferences.hideNotificationContent.get())
    }

    @Test
    fun `discreet removes titles chapters sources files expanded text and counts`() {
        Event.entries.forEach {
            val safe = transform(NotificationPrivacyLevel.DISCREET, it)
            assertSafe(safe)
            assertEquals("Pindorama!", safe.title)
        }
    }

    @Test
    fun `private reveals no identifying data for any known event`() {
        Event.entries.forEach { assertSafe(transform(NotificationPrivacyLevel.PRIVATE, it)) }
        assertEquals("Atividade disponível", transform(NotificationPrivacyLevel.PRIVATE, Event.UPDATES).text)
        assertEquals("Processo concluído", transform(NotificationPrivacyLevel.PRIVATE, Event.BACKUP_COMPLETE).text)
    }

    @Test
    fun `benign process messages remain understandable`() {
        assertEquals("Há novas atualizações", transform(NotificationPrivacyLevel.DISCREET, Event.UPDATES).text)
        assertEquals(
            "Download em andamento",
            transform(NotificationPrivacyLevel.DISCREET, Event.DOWNLOAD_PROGRESS).text,
        )
        assertEquals("Download pausado", transform(NotificationPrivacyLevel.DISCREET, Event.DOWNLOAD_PAUSED).text)
        assertEquals("Processo em andamento", transform(NotificationPrivacyLevel.PRIVATE, Event.DOWNLOAD_PROGRESS).text)
    }

    @Test
    fun `errors do not become success messages`() {
        listOf(NotificationPrivacyLevel.DISCREET, NotificationPrivacyLevel.PRIVATE).forEach {
            assertEquals("O Pindorama precisa da sua atenção", transform(it, Event.ERROR).text)
        }
    }

    @Test
    fun `unknown types fall back to generic activity`() {
        listOf(NotificationPrivacyLevel.DISCREET, NotificationPrivacyLevel.PRIVATE).forEach {
            assertEquals(Content("Pindorama!", "Atividade disponível"), transform(it, Event.UNKNOWN))
        }
    }

    @Test
    fun `repeated redaction never restores original content`() {
        val first = transform(NotificationPrivacyLevel.DISCREET, Event.UPDATES)
        val second = NotificationPrivacyPolicy.transform(
            NotificationPrivacyLevel.PRIVATE,
            Event.UPDATES,
            first,
            "Pindorama!",
            ::resolve,
        )
        assertSafe(second)
        assertEquals("Atividade disponível", second.text)
    }

    private fun transform(level: NotificationPrivacyLevel, event: Event) =
        NotificationPrivacyPolicy.transform(level, event, sensitive, "Pindorama!", ::resolve)

    private fun assertSafe(content: Content) {
        listOf(
            "Obra reservada",
            "Capítulo 42",
            "Fonte secreta",
            "backup-pessoal",
            "/storage/",
            "Nome privado",
        ).forEach {
            assertFalse(content.toString().contains(it), it)
        }
        assertEquals(0, content.number)
        assertEquals(null, content.subText)
        assertTrue(content.expandedLines.isEmpty())
        assertFalse(content.text.isNullOrBlank())
    }

    private fun resolve(resource: StringResource): String = when (resource) {
        MR.strings.pindorama_notification_updates -> "Há novas atualizações"
        MR.strings.pindorama_notification_downloading -> "Download em andamento"
        MR.strings.pindorama_notification_paused -> "Download pausado"
        MR.strings.pindorama_notification_complete -> "Processo concluído"
        MR.strings.pindorama_notification_processing -> "Processo em andamento"
        MR.strings.pindorama_notification_attention -> "O Pindorama precisa da sua atenção"
        MR.strings.pindorama_notification_activity -> "Atividade disponível"
        else -> "Processo disponível"
    }
}
