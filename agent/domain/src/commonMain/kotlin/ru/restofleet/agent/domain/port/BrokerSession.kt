package ru.restofleet.agent.domain.port

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.IncomingCommand
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

enum class SessionState { DISCONNECTED, CONNECTING, CONNECTED }

/** Сессия с брокером: состояние, входящие команды и желаемая конфигурация. Протокол MQTT скрыт за портом. */
interface BrokerSession {
    val state: StateFlow<SessionState>
    val commands: Flow<IncomingCommand>
    val desired: Flow<DesiredShadow>

    suspend fun connect(): Outcome<Unit, DomainError>
    suspend fun disconnect()
}
