package ru.restofleet.console.core.storage

/** Часть шифротекста AES-GCM: вектор инициализации и данные с тегом. */
class CipherParts(val iv: ByteArray, val ciphertext: ByteArray)

/** Порт шифрования секретов. На Android — AES-GCM ключом Keystore, на jvm реализации нет (токены в памяти). */
interface SecretCipher {
    fun encrypt(plain: ByteArray): CipherParts

    fun decrypt(parts: CipherParts): ByteArray
}

/**
 * Формат конверта: `[версия:1][длина IV:1][IV][шифротекст]`. Версия позволяет сменить алгоритм,
 * не теряя возможность прочитать (и отвергнуть) старые данные.
 */
object CipherEnvelope {
    const val VERSION: Int = 1
    private const val BYTE_MASK = 0xFF
    private const val HEADER = 2

    fun encode(parts: CipherParts): ByteArray {
        require(parts.iv.size in 1..BYTE_MASK) { "iv length" }
        return byteArrayOf(VERSION.toByte(), parts.iv.size.toByte()) + parts.iv + parts.ciphertext
    }

    /** `null` — неизвестная версия, обрезанные или пустые данные. */
    fun decode(bytes: ByteArray): CipherParts? {
        val ivLen = if (bytes.size < HEADER) 0 else bytes[1].toInt() and BYTE_MASK
        val valid = bytes.size >= HEADER && (bytes[0].toInt() and BYTE_MASK) == VERSION &&
            ivLen > 0 && bytes.size > HEADER + ivLen
        return if (valid) {
            CipherParts(
                iv = bytes.copyOfRange(HEADER, HEADER + ivLen),
                ciphertext = bytes.copyOfRange(HEADER + ivLen, bytes.size),
            )
        } else {
            null
        }
    }
}
