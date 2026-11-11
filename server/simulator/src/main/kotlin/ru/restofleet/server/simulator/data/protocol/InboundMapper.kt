package ru.restofleet.server.simulator.data.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import ru.restofleet.server.simulator.domain.model.DesiredUpdate
import ru.restofleet.server.simulator.domain.model.IncomingCommand
import ru.restofleet.server.simulator.domain.model.ProbeTarget
import ru.restofleet.server.simulator.domain.model.Thresholds
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.CommandRequestDto
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto
import ru.restofleet.shared.protocol.rest.PollCommandsResponseDto

/** Входящие сообщения (`desired`, `cmd/req`, ответ `GET /poll/commands`) → модели симулятора; битый JSON — `null`. */
object InboundMapper {
    /** `desired` из JSON; не разобрался — `null` (сообщение пропускается). */
    fun desired(json: String): DesiredUpdate? = decode { RfJson.decodeFromString(ShadowDesiredDto.serializer(), json) }
        ?.let { dto ->
            DesiredUpdate(
                version = dto.version,
                telemetryIntervalSec = dto.telemetry.intervalSec,
                thresholds = dto.thresholds.let { Thresholds(it.batteryLowPct, it.tempHighC, it.fgAbsentMin) },
                watchedApps = dto.watchedApps,
                probes = dto.probes.map {
                    ProbeTarget(
                        it.id, it.type, it.url, it.intervalSec, it.timeoutMs, it.failThreshold, it.reference,
                        it.expectedStatus,
                    )
                },
            )
        }

    /** `cmd/req` из JSON; параметры — строковое содержимое примитивов. */
    fun command(json: String, receivedAtNanos: Long, correlationData: ByteArray? = null): IncomingCommand? =
        decode { RfJson.decodeFromString(CommandRequestDto.serializer(), json) }
            ?.let { command(it, receivedAtNanos, correlationData) }

    fun command(dto: CommandRequestDto, receivedAtNanos: Long, correlationData: ByteArray? = null) = IncomingCommand(
        cmdId = dto.cmdId,
        type = dto.type,
        params = dto.params.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to it.content } }.toMap(),
        expiresAt = dto.expiresAt,
        receivedAtNanos = receivedAtNanos,
        correlationData = correlationData,
    )

    fun pollCommands(json: String, receivedAtNanos: Long): List<IncomingCommand>? =
        decode { RfJson.decodeFromString(PollCommandsResponseDto.serializer(), json) }
            ?.commands?.map { command(it, receivedAtNanos) }

    private inline fun <T> decode(block: () -> T): T? = try {
        block()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
