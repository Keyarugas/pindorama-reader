package eu.kanade.tachiyomi.data.backup.crypto

internal fun interface CryptoMemoryBudget {
    fun check(payloadSize: Int, profile: Argon2Profile)

    companion object {
        /** Conservative admission check, not a heap reservation or an OS memory guarantee. */
        val RUNTIME = CryptoMemoryBudget { payloadSize, profile ->
            val runtime = Runtime.getRuntime()
            val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            val required = requiredBytes(payloadSize, profile)
            cryptoRequire(available >= required, BackupCryptoError.INSUFFICIENT_RESOURCES)
        }

        fun requiredBytes(payloadSize: Int, profile: Argon2Profile): Long =
            4L * payloadSize + 2L * profile.memoryKiB * 1024 + 16L * 1024 * 1024
    }
}
