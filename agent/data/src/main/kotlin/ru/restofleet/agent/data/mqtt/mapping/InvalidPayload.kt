package ru.restofleet.agent.data.mqtt.mapping

import ru.restofleet.shared.kernel.DomainError

/** Входящий payload не разобрать как сообщение протокола (битый JSON, нет обязательных полей). */
data class InvalidPayload(val details: String) : DomainError {
    override val code: String = "invalid_payload"
}
