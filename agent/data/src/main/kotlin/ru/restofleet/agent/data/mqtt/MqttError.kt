package ru.restofleet.agent.data.mqtt

import ru.restofleet.agent.domain.error.MessageRejected
import ru.restofleet.shared.kernel.DomainError

/**
 * Причины, по которым MQTT-сессия не работает или брокер отказал в публикации. [code] виден в Lab и в журнале и
 * никогда не содержит секретов (ни пароля, ни текста исключения клиента). [retryable] касается только подключения:
 * имеет ли смысл повторять его без вмешательства человека с обычной паузой 1..120 с. `false` у учётки и CA, которые
 * меняются только провижинингом; `auth_failed` всё же повторяется редко (15..60 мин, решение 173).
 */
sealed class MqttError(override val code: String, val retryable: Boolean) : DomainError {
    /** Учётка брокера не сохранена. */
    data object NotConfigured : MqttError("not_configured", retryable = false)

    /** Запись учётки есть, но хранилище ключей временно не читается: запись цела, чтение повторяется (решение 174). */
    data object CredentialsUnavailable : MqttError("credentials_unavailable", retryable = true)

    /** Источник CA вернул `null` (release или нет файла в debug): системному хранилищу не доверяем. */
    data object CaMissing : MqttError("ca_missing", retryable = false)

    /** CA есть, но это не PEM-сертификат. */
    data object CaInvalid : MqttError("ca_invalid", retryable = false)

    /** Рукопожатие TLS не прошло: сертификат не от CA стенда, имя хоста не в SAN, протокол. */
    data object TlsError : MqttError("tls_error", retryable = true)

    /** CONNACK отказал по имени, паролю, `clientId` или бану. */
    data object AuthFailed : MqttError("auth_failed", retryable = false)

    /**
     * Брокер отключил сессию: тот же `clientId` подключился с другого устройства (DISCONNECT `0x8E`). Переподключение
     * идёт с длинной паузой, иначе две копии учётки выбивали бы друг друга раз в секунду (решение 176).
     */
    data object SessionTakenOver : MqttError("session_taken_over", retryable = true)

    /** Брокер отказал в публикации (PUBACK `0x87`) или подписке (SUBACK `0x87`): нет прав на топик. */
    data object NotAuthorized : MqttError("not_authorized", retryable = true), MessageRejected

    /**
     * Брокер окончательно отверг публикацию другим кодом PUBACK «не повторять» (решение 171): [reasonName] — имя кода
     * в нижнем регистре, например `topic_name_invalid`.
     */
    data class Rejected(val reasonName: String) : MqttError("puback_$reasonName", retryable = true), MessageRejected

    /** Нет подключения: публикация невозможна. */
    data object NotConnected : MqttError("not_connected", retryable = true)

    /** Сбой самого цикла сессии (исключение вне клиента); итерация повторяется с паузой (решение 175). */
    data class Internal(val detail: String) : MqttError("internal_error", retryable = true)

    /** Остальное: сеть недоступна, таймаут, другой отказ CONNACK/PUBACK. [detail] — код причины, не текст ошибки. */
    data class Failed(val detail: String) : MqttError("connect_failed", retryable = true)
}
