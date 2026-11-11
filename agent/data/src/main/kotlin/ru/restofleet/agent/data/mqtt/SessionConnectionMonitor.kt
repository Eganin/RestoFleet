package ru.restofleet.agent.data.mqtt

import kotlinx.coroutines.flow.StateFlow
import ru.restofleet.agent.domain.model.ConnectionInfo
import ru.restofleet.agent.domain.port.ConnectionMonitor

/** Экран статуса видит сессию через порт: состояние, код ошибки ([MqttError.code]), `connId` и адрес брокера. */
class SessionConnectionMonitor(private val status: StateFlow<SessionStatus>) : ConnectionMonitor {
    override fun current(): ConnectionInfo = status.value.let {
        ConnectionInfo(state = it.state, connId = it.connId, errorCode = it.error?.code, endpoint = it.endpoint)
    }
}
