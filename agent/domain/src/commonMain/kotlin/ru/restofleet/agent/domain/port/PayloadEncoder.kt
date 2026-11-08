package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.command.CommandOutcome
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.TelemetrySnapshot

/**
 * Сериализация полезной нагрузки исходящих сообщений (JSON UTF-8 в data-слое, `JsonPayloadEncoder` — задача 3.6);
 * домен формат не знает. Методы не должны зависеть от состояния: одно и то же значение даёт те же байты.
 */
interface PayloadEncoder {
    fun telemetry(s: TelemetrySnapshot): ByteArray

    fun probes(r: List<ProbeResult>): ByteArray

    /**
     * [status] — строка протокола из [ru.restofleet.agent.domain.command.CommandStatus]; [outcome] — null у
     * `received`.
     */
    fun commandResult(cmdId: String, status: String, outcome: CommandOutcome?, ts: Long): ByteArray

    fun reported(s: DesiredShadow, appliedAt: Long, errors: List<String>): ByteArray

    fun event(e: AgentEvent): ByteArray
}
