package eu.kanade.tachiyomi.network

import okhttp3.logging.HttpLoggingInterceptor
import tachiyomi.core.common.util.system.DiagnosticSanitizer

/** HEADERS diagnostics: retain protocol/status/URL, but no arbitrary header values or bodies. */
class DiagnosticHttpLogger(
    private val sink: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
) : HttpLoggingInterceptor.Logger {
    private val header = Regex("^([!#$%&'*+.^_`|~0-9A-Za-z-]+):.*$")

    override fun log(message: String) {
        val safe = header.matchEntire(message)?.let {
            "${it.groupValues[1]}: ${DiagnosticSanitizer.REDACTED}"
        } ?: DiagnosticSanitizer.sanitize(message)
        sink.log(safe)
    }
}
