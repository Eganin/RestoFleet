package ru.restofleet.agent.data.credentials

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AesGcmCipherTest {
    class TestKeys(var key: SecretKey = newKey()) : SecretKeySource {
        var discarded = 0

        override fun key() = key

        override fun discard() {
            discarded++
            key = newKey()
        }
    }

    private val keys = TestKeys()
    private val cipher = AesGcmCipher(keys)
    private val plain = "host=10.0.2.2;password=s3cr3t".toByteArray()

    @Test
    fun decryptReturnsOriginalPlainText() {
        assertThat(cipher.decrypt(cipher.encrypt(plain))).isEqualTo(plain)
    }

    @Test
    fun twoEncryptionsOfSameTextDifferInIvAndCipherText() {
        val a = cipher.encrypt(plain)
        val b = cipher.encrypt(plain)

        assertThat(a.copyOfRange(0, IV_SIZE)).isNotEqualTo(b.copyOfRange(0, IV_SIZE))
        assertThat(a.copyOfRange(IV_SIZE, a.size)).isNotEqualTo(b.copyOfRange(IV_SIZE, b.size))
    }

    @Test
    fun blobHasTwelveByteIvAndSixteenByteTag() {
        assertThat(cipher.encrypt(plain)).hasLength(IV_SIZE + plain.size + TAG_SIZE)
    }

    @Test
    fun tamperedCipherTextFailsAuthentication() {
        val blob = cipher.encrypt(plain)
        blob[IV_SIZE + 1] = (blob[IV_SIZE + 1].toInt() xor 1).toByte()

        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(blob) }
    }

    @Test
    fun tamperedIvFailsAuthentication() {
        val blob = cipher.encrypt(plain)
        blob[0] = (blob[0].toInt() xor 1).toByte()

        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(blob) }
    }

    @Test
    fun blobIsBoundToPurposeViaAad() {
        val blob = AesGcmCipher(keys, "other".toByteArray()).encrypt(plain)

        assertThrows(AEADBadTagException::class.java) { cipher.decrypt(blob) }
    }

    @Test
    fun anotherKeyCannotDecrypt() {
        val blob = cipher.encrypt(plain)

        assertThrows(GeneralSecurityException::class.java) { AesGcmCipher(TestKeys()).decrypt(blob) }
    }

    @Test
    fun truncatedBlobIsRejected() {
        for (size in listOf(0, IV_SIZE - 1, IV_SIZE + TAG_SIZE - 1)) {
            assertThrows(IllegalArgumentException::class.java) { cipher.decrypt(ByteArray(size)) }
        }
    }

    @Test
    fun discardKeyMakesPreviousBlobsUnreadable() {
        val blob = cipher.encrypt(plain)

        cipher.discardKey()

        assertThat(keys.discarded).isEqualTo(1)
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(blob) }
    }

    private companion object {
        const val IV_SIZE = 12
        const val TAG_SIZE = 16

        fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
