package eu.kanade.tachiyomi.data.backup.crypto

/** Fixed codes only. Never attach a provider exception, password, path, or payload. */
internal enum class BackupCryptoError {
    INVALID_FORMAT,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_ALGORITHM,
    UNSUPPORTED_PARAMETERS,
    SIZE_LIMIT,
    INVALID_PASSWORD_ENCODING,
    AUTHENTICATION_FAILED,
    INSUFFICIENT_RESOURCES,
    BUSY,
    IO_ERROR,
    CRYPTO_UNAVAILABLE,
}

internal class BackupCryptoException(val error: BackupCryptoError) : Exception(error.name)

internal fun cryptoRequire(condition: Boolean, error: BackupCryptoError) {
    if (!condition) throw BackupCryptoException(error)
}
