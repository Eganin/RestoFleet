package ru.restofleet.agent.data.credentials

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * `AES/GCM/NoPadding`, тег 128 бит, IV 12 байт генерирует сам шифр (на AndroidKeyStore — по
 * `setRandomizedEncryptionRequired(true)`, повторить IV нельзя). [aad] привязывает шифротекст к назначению:
 * запись с другим AAD не расшифруется. Формат блока — `IV || шифротекст || тег`.
 */
class AesGcmCipher(
    private val keys: SecretKeySource,
    private val aad: ByteArray = DEFAULT_AAD,
) : BlobCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key())
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size >= IV_SIZE + TAG_BYTES) { "ciphertext is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_SIZE))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
    }

    override fun discardKey() = keys.discard()

    companion object {
        /** AAD записи учётки, версия формата 1. */
        val DEFAULT_AAD: ByteArray = "rf_broker_creds:v1".toByteArray(Charsets.UTF_8)

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / Byte.SIZE_BITS
    }
}
