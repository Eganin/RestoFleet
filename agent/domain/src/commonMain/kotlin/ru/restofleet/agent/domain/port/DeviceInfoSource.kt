package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.capability.DeviceInfo

fun interface DeviceInfoSource {
    suspend fun deviceInfo(): DeviceInfo
}
