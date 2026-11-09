package ru.restofleet.agent.data.command

import ru.restofleet.agent.data.deviceadmin.DeviceOwnerRole
import ru.restofleet.agent.data.deviceadmin.LockTaskState
import ru.restofleet.agent.data.lifecycle.DevicePermissions
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.port.CapabilitiesSource

/**
 * C4: режим из роли DO, уровень API, выданные разрешения и состояние киоска (lock task, решение 141); опрашиваются
 * при каждом вызове — команда читает свежее.
 */
class AndroidCapabilitiesSource(
    private val role: DeviceOwnerRole,
    private val permissions: DevicePermissions,
    private val lockTask: LockTaskState,
    private val apiLevel: ApiLevel = ApiLevel.Device,
) : CapabilitiesSource {
    override suspend fun current(): Capabilities {
        val granted = buildSet {
            if (permissions.notificationsEnabled()) add(Permission.POST_NOTIFICATIONS)
            if (permissions.usageAccessGranted()) add(Permission.USAGE_ACCESS)
            if (permissions.overlayGranted()) add(Permission.SYSTEM_ALERT_WINDOW)
            if (permissions.fullScreenIntentAllowed()) add(Permission.FULL_SCREEN_INTENT)
            if (permissions.fineLocationGranted()) add(Permission.FINE_LOCATION)
            if (permissions.ignoringBatteryOptimizations()) add(Permission.IGNORE_BATTERY_OPT)
        }
        val mode = if (role.isDeviceOwner()) AgentMode.DEVICE_OWNER else AgentMode.NORMAL
        val kiosk = lockTask.isActive()
        return Capabilities(
            mode,
            apiLevel.current(),
            granted,
            kioskActive = kiosk,
            kioskPackages = if (kiosk) lockTask.allowedPackages() else emptySet(),
        )
    }
}
