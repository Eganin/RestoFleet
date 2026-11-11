package ru.restofleet.server.api.shadow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import ru.restofleet.server.domain.shadow.Shadow
import ru.restofleet.shared.protocol.ShadowJson

/**
 * Тень устройства (FR-BE-20, 21). `delta` — что из `desired` устройство ещё не применило (пустая — применено, S4);
 * `pending` — устройство не подтвердило текущую версию. `published` — только в ответе на `PUT`: `false` — брокер не
 * принял retained `desired`, повтор — при подключении устройства (реш. 233).
 */
@Serializable
data class ShadowDto(
    val deviceId: String,
    val desired: JsonObject,
    val desiredVersion: Long,
    val desiredUpdatedAt: Long,
    val reported: JsonObject? = null,
    val reportedVersion: Long? = null,
    val appliedAt: Long? = null,
    val reportedAt: Long? = null,
    val errors: List<String> = emptyList(),
    val delta: JsonObject,
    val pending: Boolean,
    val published: Boolean? = null,
)

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
