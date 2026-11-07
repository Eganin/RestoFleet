package ru.restofleet.agent.data.deviceadmin

import android.content.Context
import android.os.Build
import android.os.HardwarePropertiesManager
import ru.restofleet.agent.data.collectors.guarded
import ru.restofleet.agent.data.platform.isEmulator
import ru.restofleet.agent.domain.model.HardwareTemps
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason

/**
 * R10: текущие температуры CPU, корпуса и батареи через `HardwarePropertiesManager.getDeviceTemperatures`. Метод
 * доступен только Device Owner (иначе `SecurityException`), поэтому без роли менеджер даже не запрашивается.
 * `UNDEFINED_TEMPERATURE` отбрасывается; если значений нет вовсе — это `NoSensor` (на эмуляторе — `EmulatorStub`).
 */
class HardwarePropertiesCollector(
    private val context: Context,
    private val role: DeviceOwnerRole,
    private val fingerprint: String = Build.FINGERPRINT,
    private val hardware: String = Build.HARDWARE,
    private val manager: () -> HardwarePropertiesManager? = {
        context.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE) as? HardwarePropertiesManager
    },
) {
    @Suppress("SwallowedException") // SecurityException = роль Device Owner потеряна
    fun read(): Reading<HardwareTemps> = guarded {
        if (!role.isDeviceOwner()) return@guarded Reading.Unavailable(UnavailableReason.RequiresDeviceOwner)
        val hpm = manager()
            ?: return@guarded Reading.Unavailable(UnavailableReason.Failed("no_hardware_properties_service"))
        val temps = try {
            HardwareTemps(
                cpuC = current(hpm, HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU),
                skinC = current(hpm, HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN),
                batteryC = current(hpm, HardwarePropertiesManager.DEVICE_TEMPERATURE_BATTERY),
            )
        } catch (e: SecurityException) {
            // Роль Device Owner снята между проверкой и вызовом
            return@guarded Reading.Unavailable(UnavailableReason.RequiresDeviceOwner)
        }
        when {
            temps.cpuC.isNotEmpty() || temps.skinC.isNotEmpty() || temps.batteryC.isNotEmpty() ->
                Reading.Available(temps)
            isEmulator(hardware, fingerprint) -> Reading.Unavailable(UnavailableReason.EmulatorStub)
            else -> Reading.Unavailable(UnavailableReason.NoSensor)
        }
    }

    private fun current(hpm: HardwarePropertiesManager, type: Int): List<Float> =
        hpm.getDeviceTemperatures(type, HardwarePropertiesManager.TEMPERATURE_CURRENT)
            .filter { it != HardwarePropertiesManager.UNDEFINED_TEMPERATURE }
}
