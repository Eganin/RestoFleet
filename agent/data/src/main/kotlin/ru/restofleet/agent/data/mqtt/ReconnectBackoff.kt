package ru.restofleet.agent.data.mqtt

/**
 * Задержки повторов подключения: [initialMs], затем удвоение до [maxMs] (бриф 3.6: 1 с .. 120 с). Те же числа
 * получает автопереподключение HiveMQ; этот класс считает паузы между попытками первого подключения.
 */
internal class ReconnectBackoff(private val initialMs: Long = 1_000, private val maxMs: Long = 120_000) {
    private var current = initialMs

    fun next(): Long {
        val result = current
        current = minOf(current * 2, maxMs)
        return result
    }

    fun reset() {
        current = initialMs
    }
}

/**
 * Пауза перед следующей попыткой подключения по причине отказа (решение 173):
 * - ретрайные ошибки (сеть, TLS, `credentials_unavailable`, сбой цикла) — [regular], 1..120 с;
 * - `auth_failed` — редко, [auth] 15..60 мин: сбой DynSec на брокере (восстановили старую копию, временно отключили
 *   клиента) не должен навсегда отключить парк, а частые попытки с неверной учёткой брокеру не нужны;
 * - остальные неретрайные (`not_configured`, `ca_missing`, `ca_invalid`) — `null`: ждать смены учётки.
 * Любую паузу прерывает смена учётки; [reset] — после успешного подключения.
 */
internal class ReconnectPauses(
    private val regular: ReconnectBackoff = ReconnectBackoff(),
    private val auth: ReconnectBackoff = ReconnectBackoff(AUTH_RETRY_INITIAL_MS, AUTH_RETRY_MAX_MS),
) {
    fun next(error: MqttError): Long? = when {
        error == MqttError.AuthFailed -> auth.next()
        error.retryable -> regular.next()
        else -> null
    }

    fun reset() {
        regular.reset()
        auth.reset()
    }

    private companion object {
        const val AUTH_RETRY_INITIAL_MS = 15 * 60_000L
        const val AUTH_RETRY_MAX_MS = 60 * 60_000L
    }
}
