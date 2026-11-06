package ru.restofleet.agent.data.collectors

import ru.restofleet.agent.domain.model.AppVersion
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.DeviceResources
import ru.restofleet.agent.domain.model.ForegroundApp
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.ThermalState
import ru.restofleet.agent.domain.port.TelemetrySource

/** Реализация порта [TelemetrySource] поверх коллекторов Android API. */
class AndroidTelemetrySource(
    private val battery: BatteryCollector,
    private val thermal: ThermalCollector,
    private val network: NetworkCollector,
    private val resources: ResourceCollector,
    private val foreground: ForegroundAppCollector,
    private val appVersions: AppVersionsCollector,
) : TelemetrySource {
    override suspend fun battery(): Reading<BatteryState> = battery.read()
    override suspend fun thermal(): Reading<ThermalState> = thermal.read()
    override suspend fun network(): Reading<NetworkState> = network.read()
    override suspend fun resources(): Reading<DeviceResources> = resources.read()
    override suspend fun foregroundApp(): Reading<ForegroundApp> = foreground.read()
    override suspend fun apps(watched: List<String>): Reading<List<AppVersion>> = appVersions.read(watched)
}
