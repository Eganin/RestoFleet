package ru.restofleet.agent.data.credentials

import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException

/** Состояние записи учётки для Lab (L5); `load()` разницы между «нет» и «не расшифровалась» не показывает. */
sealed interface StoredCredentials {
    data object Missing : StoredCredentials

    data class Present(val settings: BrokerSettings) : StoredCredentials

    /** Запись была, но не расшифровалась; она стёрта, [reason] — класс сбоя (не секрет). */
    data class Unreadable(val reason: String) : StoredCredentials
}

/** Внутреннее API data-слоя для Lab: порт [CredentialStore] не расширяется (решение 138). */
fun interface CredentialInspector {
    suspend fun inspect(): StoredCredentials
}

/**
 * Учётка брокера в `SharedPreferences` (`MODE_PRIVATE`): шифруется вся запись, в prefs лежит Base64(IV || шифротекст).
 * Любой сбой чтения (плохой тег, потерянный или инвалидированный ключ, битый Base64 или формат) — запись стирается,
 * ключ сбрасывается, `load()` возвращает `null`; факт сбоя остаётся маркером `unreadable` (класс исключения) до
 * следующего `save()`. `Error` не перехватывается. Запись — `commit()`: она должна быть на диске до перезапуска.
 */
class KeystoreCredentialStore(
    private val prefs: SharedPreferences,
    private val cipher: BlobCipher,
    private val dispatcher: CoroutineDispatcher,
) : CredentialStore, CredentialInspector {
    override suspend fun save(s: BrokerSettings) = withContext(dispatcher) {
        val blob = Base64.encodeToString(cipher.encrypt(CredentialCodec.encode(s)), Base64.NO_WRAP)
        val committed = prefs.edit().putString(KEY_BLOB, blob).remove(KEY_UNREADABLE).commit()
        if (!committed) throw IOException("credentials were not committed to disk")
    }

    override suspend fun load(): BrokerSettings? = (inspect() as? StoredCredentials.Present)?.settings

    override suspend fun inspect(): StoredCredentials = withContext(dispatcher) {
        val encoded = prefs.getString(KEY_BLOB, null)
        if (encoded == null) {
            prefs.getString(KEY_UNREADABLE, null)?.let { StoredCredentials.Unreadable(it) } ?: StoredCredentials.Missing
        } else {
            read(encoded)
        }
    }

    private fun read(encoded: String): StoredCredentials =
        try {
            val plain = cipher.decrypt(Base64.decode(encoded, Base64.NO_WRAP))
            CredentialCodec.decode(plain)?.let { StoredCredentials.Present(it) } ?: erase(MALFORMED)
        } catch (e: GeneralSecurityException) {
            erase(reasonOf(e))
        } catch (e: IllegalArgumentException) {
            erase(reasonOf(e))
        } catch (e: ProviderException) {
            erase(reasonOf(e))
        }

    private fun erase(reason: String): StoredCredentials {
        cipher.discardKey()
        prefs.edit().remove(KEY_BLOB).putString(KEY_UNREADABLE, reason).commit()
        return StoredCredentials.Unreadable(reason)
    }

    private fun reasonOf(e: Exception): String = e::class.java.simpleName.ifEmpty { "unknown" }

    companion object {
        const val PREFS_NAME = "rf_broker_creds"
        private const val KEY_BLOB = "blob"
        private const val KEY_UNREADABLE = "unreadable"
        private const val MALFORMED = "malformed"
    }
}
