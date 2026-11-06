package ru.restofleet.agent.data.device

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import ru.restofleet.agent.data.platform.isEmulator
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.domain.port.DeviceInfoSource

/** Сведения об устройстве из `Build` и `DevicePolicyManager`; [agentVersion] передаёт composition root. */
class AndroidDeviceInfoSource(
    private val context: Context,
    private val agentVersion: String,
) : DeviceInfoSource {
    override suspend fun deviceInfo(): DeviceInfo {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return DeviceInfo(
            apiLevel = Build.VERSION.SDK_INT,
            model = Build.MODEL,
            isEmulator = isEmulator(Build.HARDWARE, Build.FINGERPRINT),
            mode = if (dpm.isDeviceOwnerApp(context.packageName)) AgentMode.DEVICE_OWNER else AgentMode.NORMAL,
            agentVersion = agentVersion,
        )
    }
}
