package ru.restofleet.agent.data.mqtt

import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.DeviceInfoDto
import ru.restofleet.shared.protocol.dto.DeviceStateDto
import ru.restofleet.shared.protocol.dto.StatusDto

/** Сведения об устройстве для `status online` (R6): на Android собираются из `Build`. */
data class DeviceFacts(val model: String, val manufacturer: String, val osVersion: String, val apiLevel: Int)

/** Идентификатор текущей загрузки ОС; `null` — прочитать не удалось (поле в `status` опускается). */
fun interface BootIdSource {
    fun bootId(): String?
}

/**
 * Полезная нагрузка `status`: `online` с `connId` подключения и `offline` при остановке сервиса. `role` — из
 * сохранённой учётки (провижининг, реш. 210); у учётки `DEV_CONFIG` роли нет, и поле опускается.
 */
class StatusPayloads(
    private val facts: DeviceFacts,
    private val agentVersion: String,
    private val deviceOwner: () -> Boolean,
    private val bootId: BootIdSource,
) {
    fun online(connId: String, ts: Long, role: DeviceRole? = null): ByteArray = encode(
        StatusDto(
            state = DeviceStateDto.ONLINE,
            ts = ts,
            connId = connId,
            agentVersion = agentVersion,
            mode = if (deviceOwner()) "device_owner" else "normal",
            role = role?.code,
            bootId = bootId.bootId(),
            device = DeviceInfoDto(facts.model, facts.manufacturer, facts.osVersion, facts.apiLevel),
        ),
    )

    fun offline(connId: String, ts: Long, reason: String): ByteArray =
        encode(StatusDto(state = DeviceStateDto.OFFLINE, ts = ts, connId = connId, reason = reason))

    private fun encode(dto: StatusDto): ByteArray =
        RfJson.encodeToString(StatusDto.serializer(), dto).encodeToByteArray()
}
