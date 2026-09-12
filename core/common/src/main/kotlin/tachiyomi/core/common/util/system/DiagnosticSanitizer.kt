package tachiyomi.core.common.util.system

/** Best-effort redaction for diagnostics only. Never pass the result back to a request or data store. */
object DiagnosticSanitizer {
    const val REDACTED = "[REDACTED]"

    val sensitiveHeaders = setOf(
        "Authorization", "Proxy-Authorization", "Cookie", "Set-Cookie", "auth",
        "X-Api-Key", "Api-Key", "X-Auth-Token", "X-Access-Token", "X-CSRF-Token", "X-XSRF-Token",
    )

    private val secretHeaders = Regex(
        "(?i)\\b(${sensitiveHeaders.joinToString("|") { Regex.escape(it) }})[ \\t]*:[ \\t]*[^\\r\\n]*",
    )
    private val secrets = Regex(
        """(?i)(\b(?:access[_-]?token|refresh[_-]?token|id[_-]?token|token|api[_-]?key|password|client_secret|code_verifier|jwtToken|auth|authorization|cookie|set-cookie|proxy-authorization)\b["']?\s*[:=]\s*)(?:\[REDACTED\]|(?:Bearer|Basic)[ \t]+[A-Za-z0-9._~+/=-]+|"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;&}\]]+)""",
    )
    private val credentials = Regex("(?i)\\b(Bearer|Basic)[ \\t]+[A-Za-z0-9._~+/=-]+")
    private val urls = Regex("""(?i)https?://[^\s<>"']+""")
    private val userInfo = Regex("(?i)(https?://)[^/@]+@")
    private val privatePaths =
        Regex("""(?<![\w/])(?:file://)?/(?:data/(?:user(?:_de)?/\d+|data)/|storage/|sdcard/|mnt/)[^\s"'<>]*""")
    private val quotedPaths =
        Regex("""(["'])(?:file://)?/(?:data/(?:user(?:_de)?/\d+|data)/|storage/|sdcard/|mnt/)[^\r\n]*?\1""")
    private val contentUris = Regex("""content://[^\s<>"']+""")
    private val installationId = Regex("(?i)(Installation ID[ \\t]*:[ \\t]*)[^\\r\\n]+")

    fun sanitize(value: String): String {
        var result = urls.replace(value) { match ->
            userInfo.replace(match.value.substringBefore('?').substringBefore('#')) { "${it.groupValues[1]}$REDACTED@" }
        }
        result = secretHeaders.replace(result) { "${it.groupValues[1]}: $REDACTED" }
        result = secrets.replace(result) { "${it.groupValues[1]}$REDACTED" }
        result = credentials.replace(result) { "${it.groupValues[1]} $REDACTED" }
        result = quotedPaths.replace(result) { "${it.groupValues[1]}$REDACTED${it.groupValues[1]}" }
        result = privatePaths.replace(result, REDACTED)
        result = contentUris.replace(result, REDACTED)
        return installationId.replace(result) { "${it.groupValues[1]}$REDACTED" }
    }

    /** Keep the original exception type and frames as text without forwarding its raw message to Android. */
    fun sanitizedThrowable(throwable: Throwable): Throwable =
        RuntimeException(sanitize(throwable.stackTraceToString())).apply { stackTrace = emptyArray() }
}
