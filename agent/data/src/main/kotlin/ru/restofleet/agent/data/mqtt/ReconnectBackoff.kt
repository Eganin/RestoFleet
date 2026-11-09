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
