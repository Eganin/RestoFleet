package ru.restofleet.agent.data.mqtt.mapping

import kotlinx.serialization.SerializationException
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.ProbeTargetDto
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto

internal object ShadowMapper {
    private const val HTTP = "http"

    /** Агент проверяет только HTTPS-цели, поэтому цели другого типа (`tcp`) пропускаются. */
    fun fromDto(dto: ShadowDesiredDto): DesiredShadow = DesiredShadow(
        version = dto.version,
        telemetryIntervalSec = dto.telemetry.intervalSec,
        watchedApps = dto.watchedApps,
        probes = dto.probes.filter { it.type == HTTP }.map { it.toDomain() },
    )

    /** Битый JSON и отсутствие обязательных полей — [InvalidPayload], исключение наружу не выходит. */
    fun parse(payload: ByteArray): Outcome<DesiredShadow, InvalidPayload> = try {
        Outcome.Success(fromDto(RfJson.decodeFromString(ShadowDesiredDto.serializer(), payload.decodeToString())))
    } catch (e: SerializationException) {
        Outcome.Failure(InvalidPayload("shadow/desired: ${e.message}"))
    }

    private fun ProbeTargetDto.toDomain() = ProbeTarget(id = id, url = url, timeoutMs = timeoutMs)
}
