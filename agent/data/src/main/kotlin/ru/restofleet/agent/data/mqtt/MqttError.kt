package ru.restofleet.agent.data.mqtt

import ru.restofleet.shared.kernel.DomainError

/**
 * Причины, по которым MQTT-сессия не работает. [code] виден в Lab и в журнале и никогда не содержит секретов
 * (ни пароля, ни текста исключения клиента). [retryable] — имеет ли смысл повторять подключение без вмешательства
 * человека: `false` для учётки и CA, которые меняются только провижинингом.
 */
sealed class MqttError(override val code: String, val retryable: Boolean) : DomainError {
    /** Учётка брокера не сохранена. */
    data object NotConfigured : MqttError("not_configured", retryable = false)

    /** Источник CA вернул `null` (release или нет файла в debug): системному хранилищу не доверяем. */
    data object CaMissing : MqttError("ca_missing", retryable = false)

    /** CA есть, но это не PEM-сертификат. */
    data object CaInvalid : MqttError("ca_invalid", retryable = false)

    /** Рукопожатие TLS не прошло: сертификат не от CA стенда, имя хоста не в SAN, протокол. */
    data object TlsError : MqttError("tls_error", retryable = true)

    /** CONNACK отказал по имени, паролю или `clientId`. */
    data object AuthFailed : MqttError("auth_failed", retryable = false)

    /** Брокер отказал в публикации (PUBACK `0x87`): нет прав на топик. */
    data object NotAuthorized : MqttError("not_authorized", retryable = true)

    /** Нет подключения: публикация невозможна. */
    data object NotConnected : MqttError("not_connected", retryable = true)

    /** Остальное: сеть недоступна, таймаут, другой отказ CONNACK/PUBACK. [detail] — код причины, не текст ошибки. */
    data class Failed(val detail: String) : MqttError("connect_failed", retryable = true)
}
