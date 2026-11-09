package ru.restofleet.agent.domain.port

/**
 * Только debug (проверка M2): одна публикация QoS 1 в топик чужого устройства. Брокер должен отказать (PUBACK `0x87`).
 * В release реализации нет.
 */
fun interface ForeignTopicPublisher {
    suspend fun publish(): ForeignPublishResult
}

/** [accepted] — брокер принял публикацию (для ACL это ошибка); [reason] — код причины PUBACK или ошибка. */
data class ForeignPublishResult(val accepted: Boolean, val reason: String)
