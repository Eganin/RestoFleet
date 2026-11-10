package ru.restofleet.agent.data.credentials

import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.AEADBadTagException

/** Состояние записи учётки для Lab (L5); `load()` разницы между «нет» и «не расшифровалась» не показывает. */
sealed interface StoredCredentials {
    data object Missing : StoredCredentials

    data class Present(val settings: BrokerSettings) : StoredCredentials

    /** Запись была, но не расшифровалась окончательно; она стёрта, [reason] — класс сбоя (не секрет). */
    data class Unreadable(val reason: String) : StoredCredentials

    /** Хранилище ключей временно не читается; запись и ключ на месте, следующее чтение повторит попытку. */
    data class Unavailable(val reason: String) : StoredCredentials
}

/** `load()` при временном сбое хранилища ключей: запись цела, повторить позже. [reason] — класс сбоя (не секрет). */
class CredentialsUnavailableException(val reason: String) : IOException("credential store unavailable: $reason")

/** Внутреннее API data-слоя для Lab: порт [CredentialStore] не расширяется (решение 138). */
fun interface CredentialInspector {
    suspend fun inspect(): StoredCredentials
}

/**
 * Учётка брокера в `SharedPreferences` (`MODE_PRIVATE`): шифруется вся запись, в prefs лежит Base64(IV || шифротекст).
 *
 * Сбои чтения делятся на два вида (решение 174):
 * - окончательные — подменённый тег (`AEADBadTagException`; так же проявляется и потерянный ключ: вместо него создаётся
 *   новый), инвалидированный ключ (`KeyPermanentlyInvalidatedException`), битый Base64
 *   или формат: запись стирается, ключ сбрасывается, `load()` возвращает `null`, маркер `unreadable` (класс
 *   исключения) остаётся до следующего `save()`;
 * - временные — любой другой `GeneralSecurityException` или `ProviderException` (keystore2 «system error» после OTA,
 *   нехватка памяти), в том числе `UnrecoverableKeyException`: в него `AndroidKeyStoreProvider` заворачивает и
 *   временные ошибки keystore2 («Failed to obtain information about key»): запись и ключ не трогаются, `inspect()`
 *   даёт `Unavailable`, `load()` бросает [CredentialsUnavailableException], сессия повторяет чтение по паузе.
 *
 * `Error` не перехватывается. Запись — `commit()`: она должна быть на диске до перезапуска.
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

    override suspend fun load(): BrokerSettings? = when (val state = inspect()) {
        is StoredCredentials.Present -> state.settings
        is StoredCredentials.Unavailable -> throw CredentialsUnavailableException(state.reason)
        StoredCredentials.Missing, is StoredCredentials.Unreadable -> null
    }

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
            if (e.isPermanent()) erase(reasonOf(e)) else StoredCredentials.Unavailable(reasonOf(e))
        } catch (e: IllegalArgumentException) {
            erase(reasonOf(e))
        } catch (e: ProviderException) {
            StoredCredentials.Unavailable(reasonOf(e))
        }

    private fun GeneralSecurityException.isPermanent(): Boolean =
        this is AEADBadTagException || this is KeyPermanentlyInvalidatedException

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
