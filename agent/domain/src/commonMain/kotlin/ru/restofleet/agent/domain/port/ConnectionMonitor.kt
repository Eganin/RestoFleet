package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.model.ConnectionInfo

/** Текущее состояние MQTT-сессии с причиной ошибки и `connId` — для экрана статуса; читается без ожидания. */
fun interface ConnectionMonitor {
    fun current(): ConnectionInfo
}
