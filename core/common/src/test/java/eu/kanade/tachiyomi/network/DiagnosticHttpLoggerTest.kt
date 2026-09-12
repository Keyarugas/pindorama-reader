package eu.kanade.tachiyomi.network

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticHttpLoggerTest {
    @Test
    fun `unknown headers are redacted while response status survives`() {
        val messages = mutableListOf<String>()
        val logger = DiagnosticHttpLogger { messages.add(it) }
        logger.log("X-Custom-Session: secret")
        logger.log("<-- 403 https://example.org/chapter?token=secret (20ms)")
        assertEquals("X-Custom-Session: [REDACTED]", messages[0])
        assertEquals("<-- 403 https://example.org/chapter (20ms)", messages[1])
    }

    @Test
    fun `interceptor does not change requests responses or read bodies for logging`() {
        val messages = mutableListOf<String>()
        val interceptor = HttpLoggingInterceptor(DiagnosticHttpLogger { messages.add(it) }).apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
        val request = Request.Builder()
            .url("https://example.org/chapter?token=secret#fragment")
            .header("Authorization", "Bearer private-token")
            .header("Cookie", "session=private-cookie")
            .build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Set-Cookie", "session=response-cookie")
            .body("private-body".toResponseBody())
            .build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.connection() } returns null
        every { chain.proceed(request) } returns response

        assertSame(response, interceptor.intercept(chain))
        verify(exactly = 1) { chain.proceed(request) }
        assertEquals("Bearer private-token", request.header("Authorization"))
        assertEquals("session=response-cookie", response.header("Set-Cookie"))
        assertEquals("private-body", response.body.string())
        assertTrue(messages.any { it.contains("https://example.org/chapter") })
        listOf("secret", "fragment", "private-token", "private-cookie", "response-cookie", "private-body").forEach {
            assertFalse(messages.joinToString().contains(it), it)
        }
    }
}
