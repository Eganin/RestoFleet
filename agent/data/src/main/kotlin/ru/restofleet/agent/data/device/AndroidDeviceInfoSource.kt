package ru.restofleet.agent.data.device

import android.content.Context
import android.os.Build
import ru.restofleet.agent.data.deviceadmin.DeviceOwnerRole
import ru.restofleet.agent.data.platform.isEmulator
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.domain.port.DeviceInfoSource

/**
 * Сведения об устройстве из `Build`; режим — по единому источнику роли [role] (решение 110); [agentVersion] передаёт
 * composition root.
 */
class AndroidDeviceInfoSource(
    private val context: Context,
    private val agentVersion: String,
    private val role: DeviceOwnerRole,
) : DeviceInfoSource {
    override suspend fun deviceInfo(): DeviceInfo {
        return DeviceInfo(
            apiLevel = Build.VERSION.SDK_INT,
            model = Build.MODEL,
            isEmulator = isEmulator(Build.HARDWARE, Build.FINGERPRINT),
            mode = if (role.isDeviceOwner()) AgentMode.DEVICE_OWNER else AgentMode.NORMAL,
            agentVersion = agentVersion,
        )
    }
}
