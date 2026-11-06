package ru.restofleet.agent.data.collectors

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import ru.restofleet.agent.domain.model.AppVersion
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.DeviceResources
import ru.restofleet.agent.domain.model.ForegroundApp
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.ThermalState
import ru.restofleet.agent.domain.port.TelemetrySource

/**
 * Реализация порта [TelemetrySource] поверх коллекторов Android API.
 *
 * Считывания блокирующие (binder-вызовы), поэтому идут на [io] через `runInterruptible`: зависшее считывание
 * не держит поток вызывающего, а таймаут `CollectTelemetry` прерывает поток там, где платформа это поддерживает.
 * Непрерываемый binder-вызов прервать нельзя — это отложено на M3.
 */
class AndroidTelemetrySource(
    private val battery: BatteryCollector,
    private val thermal: ThermalCollector,
    private val network: NetworkCollector,
    private val resources: ResourceCollector,
    private val foreground: ForegroundAppCollector,
    private val appVersions: AppVersionsCollector,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TelemetrySource {
    override suspend fun battery(): Reading<BatteryState> = runInterruptible(io) { battery.read() }
    override suspend fun thermal(): Reading<ThermalState> = runInterruptible(io) { thermal.read() }
    override suspend fun network(): Reading<NetworkState> = runInterruptible(io) { network.read() }
    override suspend fun resources(): Reading<DeviceResources> = runInterruptible(io) { resources.read() }
    override suspend fun foregroundApp(): Reading<ForegroundApp> = runInterruptible(io) { foreground.read() }
    override suspend fun apps(watched: List<String>): Reading<List<AppVersion>> =
        runInterruptible(io) { appVersions.read(watched) }
}
