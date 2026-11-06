package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.domain.port.DeviceInfoSource

/** Сведения об устройстве и режиме агента — для шапки Lab и отчёта. */
class GetDeviceInfo(private val source: DeviceInfoSource) {
    suspend operator fun invoke(): DeviceInfo = source.deviceInfo()
}
