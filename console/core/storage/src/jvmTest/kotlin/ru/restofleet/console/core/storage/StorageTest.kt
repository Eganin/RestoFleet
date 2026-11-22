package ru.restofleet.console.core.storage

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StorageTest {
    /** Тестовый «шифр»: XOR с ключом, IV — счётчик. Достаточно, чтобы проверить конверт и хранилище. */
    private class XorCipher : SecretCipher {
        var counter: Byte = 1
        var broken = false

        override fun encrypt(plain: ByteArray): CipherParts =
            CipherParts(byteArrayOf(counter++), ByteArray(plain.size) { (plain[it].toInt() xor KEY).toByte() })

        override fun decrypt(parts: CipherParts): ByteArray {
            check(!broken) { "key lost" }
            return ByteArray(parts.ciphertext.size) { (parts.ciphertext[it].toInt() xor KEY).toByte() }
        }

        companion object {
            const val KEY = 0x5A
        }
    }

    private val session = StoredSession("https://rf.stand.local", "support.ivanova", "refresh-secret")

    @Test
    fun `envelope round trip keeps iv and ciphertext`() {
        val parts = CipherParts(byteArrayOf(1, 2, 3), byteArrayOf(9, 8, 7, 6))

        val decoded = CipherEnvelope.decode(CipherEnvelope.encode(parts))!!

        assertThat(decoded.iv).isEqualTo(parts.iv)
        assertThat(decoded.ciphertext).isEqualTo(parts.ciphertext)
    }

    @Test
    fun `envelope rejects malformed input`() {
        val valid = CipherEnvelope.encode(CipherParts(byteArrayOf(1, 2), byteArrayOf(3)))
        val rows = mapOf(
            "empty" to byteArrayOf(),
            "short" to byteArrayOf(1),
            "future version" to valid.copyOf().also { it[0] = 2 },
            "zero iv" to byteArrayOf(1, 0, 5, 5),
            "iv without data" to byteArrayOf(1, 2, 7, 7),
        )
        rows.forEach { (name, bytes) -> assertThat(CipherEnvelope.decode(bytes)).isNull().also { println(name) } }
        assertThat(CipherEnvelope.VERSION).isEqualTo(1)
    }

    @Test
    fun `envelope refuses empty iv on encode`() {
        val result = runCatching { CipherEnvelope.encode(CipherParts(byteArrayOf(), byteArrayOf(1))) }
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `encrypted store round trips and never writes plain text`() = runTest {
        val kv = InMemoryKeyValueStore()
        val store = EncryptedTokenStore(kv, XorCipher())

        store.save(session)

        assertThat(store.load()).isEqualTo(session)
        assertThat(kv.get("session.v1")).doesNotContain("refresh-secret")
    }

    @Test
    fun `undecryptable record is dropped`() = runTest {
        val kv = InMemoryKeyValueStore()
        val cipher = XorCipher()
        val store = EncryptedTokenStore(kv, cipher)
        store.save(session)
        cipher.broken = true

        assertThat(store.load()).isNull()
        assertThat(kv.get("session.v1")).isNull()
    }

    @Test
    fun `garbage record is dropped and empty store is null`() = runTest {
        val kv = InMemoryKeyValueStore()
        val store = EncryptedTokenStore(kv, XorCipher())
        assertThat(store.load()).isNull()

        kv.put("session.v1", "not base64!!")
        assertThat(store.load()).isNull()
        assertThat(kv.get("session.v1")).isNull()
    }

    @Test
    fun `clear removes the session`() = runTest {
        val store = EncryptedTokenStore(InMemoryKeyValueStore(), XorCipher())
        store.save(session)

        store.clear()

        assertThat(store.load()).isNull()
    }

    @Test
    fun `in memory store behaves like a store and hides the token in toString`() = runTest {
        val store = InMemoryTokenStore()
        assertThat(store.load()).isNull()
        store.save(session)
        assertThat(store.load()).isEqualTo(session)
        assertThat(session.toString()).doesNotContain("refresh-secret")
        store.clear()
        assertThat(store.load()).isNull()
    }
}
