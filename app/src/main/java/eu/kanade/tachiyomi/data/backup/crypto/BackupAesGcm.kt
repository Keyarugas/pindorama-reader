package eu.kanade.tachiyomi.data.backup.crypto

import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object BackupAesGcm {
    fun encrypt(payload: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(payload)

    fun decrypt(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray, aad: ByteArray): ByteArray {
        cryptoRequire(ciphertext.size >= PindobkFormat.TAG_SIZE, BackupCryptoError.INVALID_FORMAT)
        val cipher = cipher(Cipher.DECRYPT_MODE, key, nonce, aad)
        // Private output buffer: providers may write unauthenticated bytes during doFinal.
        val plaintext = ByteArray(ciphertext.size - PindobkFormat.TAG_SIZE)
        try {
            val count = cipher.doFinal(ciphertext, 0, ciphertext.size, plaintext, 0)
            cryptoRequire(count == plaintext.size, BackupCryptoError.INVALID_FORMAT)
            return plaintext
        } catch (_: BadPaddingException) {
            plaintext.fill(0)
            throw BackupCryptoException(BackupCryptoError.AUTHENTICATION_FAILED)
        } catch (e: Throwable) {
            plaintext.fill(0)
            throw e
        }
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        cryptoRequire(key.size == 32 && nonce.size == PindobkFormat.NONCE_SIZE, BackupCryptoError.INVALID_FORMAT)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
        }
    }
}
