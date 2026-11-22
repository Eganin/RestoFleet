package ru.restofleet.server.api.shadow

import ru.restofleet.server.domain.shadow.Shadow
import ru.restofleet.shared.protocol.ShadowJson
import ru.restofleet.shared.protocol.rest.console.ShadowDto

internal fun Shadow.toDto() = ShadowDto(
    deviceId = deviceId.value,
    desired = ShadowJson.toJson(desired),
    desiredVersion = desiredVersion,
    desiredUpdatedAt = desiredUpdatedAt,
    reported = reported?.let(ShadowJson::toJson),
    reportedVersion = reportedVersion,
    appliedAt = appliedAt,
    reportedAt = reportedAt,
    errors = errors,
    delta = ShadowJson.toJson(delta),
    pending = isPending,
)
