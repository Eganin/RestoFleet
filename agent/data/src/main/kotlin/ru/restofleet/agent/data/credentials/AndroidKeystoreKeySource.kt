package ru.restofleet.agent.data.credentials

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Ключ AES-256 в `AndroidKeyStore` под алиасом [alias]: создаётся при первом обращении, без привязки к экрану
 * блокировки (агент работает без пользователя), материал ключа из хранилища не извлекается. Требует устройства:
 * на JVM недоступен, поэтому класс проверяется L5 на эмуляторе, а логика вокруг — тестами с обычным `KeyGenerator`.
 */
class AndroidKeystoreKeySource(private val alias: String = KEY_ALIAS) : SecretKeySource {
    @Synchronized
    override fun key(): SecretKey = (keyStore().getKey(alias, null) as? SecretKey) ?: generate()

    @Synchronized
    override fun discard() {
        try {
            keyStore().deleteEntry(alias)
        } catch (_: KeyStoreException) {
            // Ключа уже нет или хранилище недоступно: следующая запись всё равно создаст ключ заново либо упадёт сама.
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun generate(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    companion object {
        const val KEY_ALIAS = "rf_broker_creds"
        private const val PROVIDER = "AndroidKeyStore"
        private const val KEY_BITS = 256
    }
}
