package tachiyomi.core.common.util.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticSanitizerTest {
    @Test
    fun `sensitive headers are redacted case insensitively including logcat prefixes`() {
        DiagnosticSanitizer.sensitiveHeaders.forEach { header ->
            val input = "09-12 12:00:00 I okhttp: ${header.lowercase()}: secret; session=other"
            val output = DiagnosticSanitizer.sanitize(input)
            assertFalse(output.contains("secret"))
            assertFalse(output.contains("other"))
            assertTrue(output.startsWith("09-12 12:00:00 I okhttp:"))
        }
    }

    @Test
    fun `URL query is removed entirely`() {
        assertEquals(
            "GET https://example.org/chapter/42",
            sanitize("GET https://example.org/chapter/42?token=secret&p=1"),
        )
    }

    @Test
    fun `URL fragment is removed`() {
        assertEquals("https://example.org/login", sanitize("https://example.org/login#access_token=secret"))
    }

    @Test
    fun `URL credentials are removed`() {
        assertEquals("https://[REDACTED]@example.org/path", sanitize("https://user:password@example.org/path?q=secret"))
    }

    @Test
    fun `ordinary URL is preserved`() {
        assertEquals("https://example.org/chapter/42", sanitize("https://example.org/chapter/42"))
    }

    @Test
    fun `ordinary stack trace is preserved exactly`() {
        val trace = "java.io.IOException: timeout\n" +
            "\tat example.Reader.load(Reader.kt:42)\nCaused by: java.net.SocketException"
        assertEquals(trace, sanitize(trace))
    }

    @Test
    fun `private Android paths are removed`() {
        listOf(
            "/data/user/0/app.pindorama.reader/files/session.json",
            "/data/user_de/0/app.pindorama.reader/files/session.json",
            "/data/data/app.pindorama.reader/shared_prefs/settings.xml",
            "/storage/emulated/0/Pindorama/downloads/title/chapter.jpg",
            "file:///sdcard/Pindorama/backup.tachibk",
            "content://provider/tree/secret/document/title",
        ).forEach { path -> assertEquals("Failed: [REDACTED]", sanitize("Failed: $path")) }
    }

    @Test
    fun `quoted private path with spaces is removed`() {
        assertEquals("Failed: '[REDACTED]'", sanitize("Failed: '/storage/emulated/0/Private title/page 1.jpg'"))
    }

    @Test
    fun `named secrets and bearer credentials are removed`() {
        listOf("access_token", "refresh_token", "apiKey", "password", "client_secret", "code_verifier").forEach {
            assertEquals("$it=[REDACTED]", sanitize("$it=secret"), it)
            assertFalse(sanitize("{\"$it\":\"sensitive value\"}").contains("sensitive value"), it)
        }
        assertEquals("Authorization=[REDACTED]", sanitize("Authorization=Bearer private-token"))
        assertEquals("auth=[REDACTED]", sanitize("auth=private-token"))
        assertEquals("Bearer [REDACTED]", sanitize("Bearer abc.def.ghi"))
        assertEquals("Basic [REDACTED]", sanitize("Basic dXNlcjpwYXNz"))
    }

    @Test
    fun `installation identifier is redacted in legacy diagnostics`() {
        assertEquals("Installation ID: [REDACTED]", sanitize("Installation ID: stable-identifier"))
    }

    @Test
    fun `benign diagnostics and identifiers are preserved`() {
        val value = "mangaId=42 chapterId=7 sourceId=100 HTTP 403 timeout=30ms Reader.kt:12"
        assertEquals(value, sanitize(value))
        assertEquals(
            "tokenization failed; Cookie parsing failed",
            sanitize("tokenization failed; Cookie parsing failed"),
        )
        assertEquals("", sanitize(""))
    }

    @Test
    fun `sanitized throwable preserves original frames without exposing cause secrets`() {
        val original =
            IllegalStateException("https://example.org/path?secret=yes", RuntimeException("Cookie: session=abc"))
        val safe = DiagnosticSanitizer.sanitizedThrowable(original).stackTraceToString()
        assertFalse(safe.contains("secret=yes"))
        assertFalse(safe.contains("session=abc"))
        assertTrue(safe.contains("IllegalStateException"))
        assertTrue(safe.contains("DiagnosticSanitizerTest.kt:"))
        assertEquals("Cookie: session=abc", original.cause?.message)
    }

    @Test
    fun `redaction can be applied again at export`() {
        val once = sanitize("access_token=secret\nAuthorization: Bearer secret\nhttps://example.org/a?token=secret")
        assertEquals(once, sanitize(once))
    }

    private fun sanitize(value: String) = DiagnosticSanitizer.sanitize(value)
}
