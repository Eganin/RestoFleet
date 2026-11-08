package ru.restofleet.agent.data.deviceadmin

import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.shared.kernel.Clock

/** D1: приложение — Device Owner. Роль читается при каждом запуске (её назначают командой `dpm` на лету). */
class DeviceOwnerCheck(
    private val clock: Clock,
    private val capabilities: CapabilitiesSource,
) : CapabilityCheck {
    override val id: CheckId = CheckId.D1

    override suspend fun run(): CheckResult {
        val mode = capabilities.current().mode
        val isOwner = mode == AgentMode.DEVICE_OWNER
        return CheckResult(
            id,
            if (isOwner) CheckStatus.OK else CheckStatus.UNAVAILABLE,
            mapOf("mode" to mode.name),
            "not_device_owner".takeUnless { isOwner },
            clock.nowMillis(),
        )
    }
}
