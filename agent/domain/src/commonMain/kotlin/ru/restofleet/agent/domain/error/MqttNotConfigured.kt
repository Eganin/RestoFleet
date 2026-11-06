package ru.restofleet.agent.domain.error

import ru.restofleet.shared.kernel.DomainError

/** Публикация в MQTT невозможна: клиент ещё не подключён (до задачи 3.6 всегда такой). */
data object MqttNotConfigured : DomainError {
    override val code: String = "mqtt_not_configured"
}
