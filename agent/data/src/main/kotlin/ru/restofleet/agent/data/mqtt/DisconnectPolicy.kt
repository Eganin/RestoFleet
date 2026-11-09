package ru.restofleet.agent.data.mqtt

import kotlin.random.Random

/**
 * Что делать с обрывом соединения HiveMQ. Решение отделено от клиента, чтобы проверять его без брокера; [HiveMqLink]
 * только применяет его к `MqttClientReconnector`.
 *
 * - своё закрытие или упавшее первое подключение: не переподключаться, первое подключение повторяет сессия;
 * - отказ учётки после реконнекта: не переподключаться, сессии `Closed`;
 * - `session_taken_over` (`0x8E`, та же учётка на другом устройстве): переподключение через 5 мин + до 1 мин jitter,
 *   иначе две копии выбивали бы друг друга каждую секунду (решение 176);
 * - остальное: пауза HiveMQ 1..120 с, сессии `Lost` только на первой попытке.
 */
internal class DisconnectPolicy(private val random: Random = Random.Default) {
    sealed interface Action {
        /** Сообщение для сессии (`null` — сообщать нечего). */
        val notify: LinkEvent?

        /** Клиент больше не переподключается. */
        data class Stop(override val notify: LinkEvent?) : Action

        /** Переподключение с новым `connId`; [delayMs] — своя пауза вместо паузы HiveMQ (`null` — пауза HiveMQ). */
        data class Reconnect(val delayMs: Long?, override val notify: LinkEvent?) : Action
    }

    fun decide(error: MqttError, closing: Boolean, connectedOnce: Boolean, attempts: Int): Action = when {
        closing || !connectedOnce -> Action.Stop(null)
        !error.retryable -> Action.Stop(LinkEvent.Closed(error))
        else -> Action.Reconnect(
            delayMs = if (error == MqttError.SessionTakenOver) takeoverDelayMs() else null,
            notify = if (attempts == 0) LinkEvent.Lost(error) else null,
        )
    }

    private fun takeoverDelayMs(): Long = TAKEOVER_DELAY_MS + random.nextLong(TAKEOVER_JITTER_MS + 1)

    private companion object {
        const val TAKEOVER_DELAY_MS = 5 * 60_000L
        const val TAKEOVER_JITTER_MS = 60_000L
    }
}
