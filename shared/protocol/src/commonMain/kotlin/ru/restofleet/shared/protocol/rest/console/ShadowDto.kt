package ru.restofleet.shared.protocol.rest.console

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Тень устройства (FR-BE-20, 21). `delta` — что из `desired` устройство ещё не применило (пустая — применено, S4);
 * `pending` — устройство не подтвердило текущую версию. `published` — только в ответе на `PUT`: `false` — брокер не
 * принял retained `desired`, повтор — при подключении устройства (реш. 233). `PUT` принимает merge-patch к `desired`
 * (реш. 237); перед отправкой консоль проверяет итог через `shared:shadow` `validateDesired`.
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
