package ru.restofleet.agent.domain.fake

import ru.restofleet.agent.domain.model.AppVersion
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.DeviceResources
import ru.restofleet.agent.domain.model.ForegroundApp
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.NetworkType
import ru.restofleet.agent.domain.model.PowerSource
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.ThermalState
import ru.restofleet.agent.domain.model.ThermalStatus
import ru.restofleet.agent.domain.port.TelemetrySource

class FakeTelemetrySource : TelemetrySource {
    var batteryResult: () -> Reading<BatteryState> = { Reading.Available(BATTERY) }
    var thermalResult: () -> Reading<ThermalState> = { Reading.Available(THERMAL) }
    var networkResult: () -> Reading<NetworkState> = { Reading.Available(NETWORK) }
    var resourcesResult: () -> Reading<DeviceResources> = { Reading.Available(RESOURCES) }
    var foregroundResult: () -> Reading<ForegroundApp> = { Reading.Available(FOREGROUND) }
    var appsResult: (List<String>) -> Reading<List<AppVersion>> = { Reading.Available(APPS) }

    override suspend fun battery() = batteryResult()
    override suspend fun thermal() = thermalResult()
    override suspend fun network() = networkResult()
    override suspend fun resources() = resourcesResult()
    override suspend fun foregroundApp() = foregroundResult()
    override suspend fun apps(watched: List<String>) = appsResult(watched)

    companion object {
        val BATTERY = BatteryState(80, true, PowerSource.AC, 31.5)
        val THERMAL = ThermalState(ThermalStatus.LIGHT, 0.4)
        val NETWORK = NetworkState(NetworkType.WIFI, -55, true)
        val RESOURCES = DeviceResources(2048, 1024, 3600, "14", 34, "Pixel", "Google")
        val FOREGROUND = ForegroundApp("ru.restofleet.kiosk", 1000L)
        val APPS = listOf(AppVersion("ru.restofleet.kiosk", "1.0", 10L))
    }
}
