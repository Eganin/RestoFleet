package ru.restofleet.agent.data.deviceadmin

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Сверка SHA-256 файла с ожидаемым значением. Файл читается потоком, в память целиком не загружается. */
object Sha256Verifier {
    private const val HEX_LENGTH = 64
    private const val BUFFER_SIZE = 8 * 1024
    private const val HEX_RADIX = 16
    private val HEX = Regex("[0-9a-fA-F]{$HEX_LENGTH}")

    /** `true`, если [expected] — корректная запись из 64 hex-цифр (регистр не важен). */
    fun isValid(expected: String): Boolean = HEX.matches(expected)

    /** Регистр hex не важен; пустой или некорректный [expectedHex], а также нечитаемый файл — `false`. */
    fun matches(file: File, expectedHex: String): Boolean =
        isValid(expectedHex) && digestOrNull(file)?.let { MessageDigest.isEqual(it, decode(expectedHex)) } == true

    @Suppress("SwallowedException") // нечитаемый файл — не совпадение, причина вызывающему не нужна
    private fun digestOrNull(file: File): ByteArray? =
        try {
            digest(file)
        } catch (e: IOException) {
            null
        }

    private fun digest(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest()
    }

    private fun decode(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
}
