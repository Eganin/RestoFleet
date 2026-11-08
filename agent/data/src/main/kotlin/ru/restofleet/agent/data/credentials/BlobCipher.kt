package ru.restofleet.agent.data.credentials

import javax.crypto.SecretKey

/** Шифрование записи целиком: `encrypt` отдаёт `IV || шифротекст+тег`, `decrypt` принимает такой же блок. */
interface BlobCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /**
     * Бросает `GeneralSecurityException` (в т.ч. `AEADBadTagException`) или `IllegalArgumentException`
     * для обрезанного блока.
     */
    fun decrypt(blob: ByteArray): ByteArray

    /** Забыть ключ: следующий `encrypt` создаст новый. Вызывается, когда запись нельзя прочитать. */
    fun discardKey()
}

/** Источник симметричного ключа; в продакшене — `AndroidKeyStore`, в тестах — обычный `KeyGenerator`. */
interface SecretKeySource {
    fun key(): SecretKey

    fun discard()
}
