package ru.restofleet.console.core.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * Сессия шифруется [SecretCipher], конверт кодируется в Base64 и кладётся в [KeyValueStore] (реш. 420).
 * Если расшифровать нельзя (ключ потерян, данные повреждены) — запись удаляется и вход нужен заново.
 */
class EncryptedTokenStore(
    private val storage: KeyValueStore,
    private val cipher: SecretCipher,
) : TokenStore {
    @Serializable
    private class Payload(val server: String, val login: String, val refreshToken: String)

    override suspend fun load(): StoredSession? {
        val raw = storage.get(KEY) ?: return null
        val session = runCatching { decrypt(raw) }.getOrNull()
        if (session == null) storage.remove(KEY)
        return session
    }

    override suspend fun save(session: StoredSession) {
        val payload = Payload(session.server, session.login, session.refreshToken)
        val json = Json.encodeToString(Payload.serializer(), payload)
        val envelope = CipherEnvelope.encode(cipher.encrypt(json.encodeToByteArray()))
        storage.put(KEY, Base64.encode(envelope))
    }

    override suspend fun clear() {
        storage.remove(KEY)
    }

    private fun decrypt(raw: String): StoredSession? {
        val parts = CipherEnvelope.decode(Base64.decode(raw)) ?: return null
        val p = Json.decodeFromString(Payload.serializer(), cipher.decrypt(parts).decodeToString())
        return StoredSession(p.server, p.login, p.refreshToken)
    }

    private companion object {
        const val KEY = "session.v1"
    }
}
