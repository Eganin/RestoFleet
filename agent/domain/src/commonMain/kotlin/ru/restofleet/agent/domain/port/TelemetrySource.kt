package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.model.AppVersion
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.DeviceResources
import ru.restofleet.agent.domain.model.ForegroundApp
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.ThermalState

interface TelemetrySource {
    suspend fun battery(): Reading<BatteryState>
    suspend fun thermal(): Reading<ThermalState>
    suspend fun network(): Reading<NetworkState>
    suspend fun resources(): Reading<DeviceResources>
    suspend fun foregroundApp(): Reading<ForegroundApp>
    suspend fun apps(watched: List<String>): Reading<List<AppVersion>>
}
