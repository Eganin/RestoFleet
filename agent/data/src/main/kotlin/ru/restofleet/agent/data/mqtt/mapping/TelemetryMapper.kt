package ru.restofleet.agent.data.mqtt.mapping

import ru.restofleet.agent.domain.model.AppVersion
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.model.ThermalState
import ru.restofleet.shared.protocol.dto.AppVersionDto
import ru.restofleet.shared.protocol.dto.BatteryDto
import ru.restofleet.shared.protocol.dto.MemoryDto
import ru.restofleet.shared.protocol.dto.NetDto
import ru.restofleet.shared.protocol.dto.StorageDto
import ru.restofleet.shared.protocol.dto.TelemetryDto
import ru.restofleet.shared.protocol.dto.ThermalDto

/**
 * Снимок телеметрии -> DTO. Недоступные считывания (`null`) не пишутся: поле опускается (`explicitNulls = false`).
 * Модель и ОС устройства в телеметрию не входят, они уходят в `status` (R6).
 */
internal object TelemetryMapper {
    fun toDto(snapshot: TelemetrySnapshot): TelemetryDto = TelemetryDto(
        ts = snapshot.ts,
        seq = snapshot.seq,
        battery = snapshot.battery?.toDto(),
        thermal = snapshot.thermal?.toDto(),
        net = snapshot.network?.let(::netToDto),
        storage = snapshot.resources?.let { StorageDto(it.freeStorageMb) },
        memory = snapshot.resources?.let { MemoryDto(it.availMemoryMb) },
        uptimeSec = snapshot.resources?.uptimeSec,
        foregroundApp = snapshot.foregroundApp?.pkg,
        apps = snapshot.apps.map { it.toDto() },
    )

    fun netToDto(state: NetworkState): NetDto = NetDto(
        type = state.type.name.lowercase(),
        rssi = state.rssiDbm,
        validated = state.validated,
    )

    private fun BatteryState.toDto() = BatteryDto(
        level = levelPct,
        charging = charging,
        plugged = source.name.lowercase(),
        tempC = tempC,
    )

    /** Пустое тепловое состояние (ни статуса, ни запаса) не передаётся. */
    private fun ThermalState.toDto(): ThermalDto? =
        if (status == null && headroom == null) null else ThermalDto(status = status?.name, headroom = headroom)

    private fun AppVersion.toDto() = AppVersionDto(pkg = pkg, versionName = versionName, versionCode = versionCode)
}
