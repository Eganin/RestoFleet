package ru.restofleet.agent.domain.model

import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.agent.domain.probe.ProbeStatus

/** Подключение к брокеру сейчас: [errorCode] объясняет `DISCONNECTED`/`CONNECTING`, [connId] — у `CONNECTED`. */
data class ConnectionInfo(
    val state: SessionState,
    val connId: String? = null,
    val errorCode: String? = null,
    val endpoint: String? = null,
)

/**
 * Снимок для экрана статуса (FR-AG-05): регистрация, подключение, режим, выданные разрешения, размер outbox,
 * последние результаты проб, версия агента. `null` у поля — значение не прочиталось (сбой источника изолирован).
 */
data class AgentStatus(
    val registration: RegistrationState,
    val connection: ConnectionInfo,
    val mode: AgentMode?,
    val agentVersion: String?,
    val granted: Set<Permission>?,
    val outboxSize: Int?,
    val probes: List<ProbeStatus>,
)
