package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.agent.domain.port.CheckResultJournal
import ru.restofleet.agent.domain.port.DeviceAdmin
import ru.restofleet.shared.kernel.Clock

/**
 * D6: самовыдача разрешений (только Device Owner). OK — выданы все, LIMITED — часть не выдана; `raw` — карта
 * `разрешение -> true/false`. Не Device Owner — NOT_APPLICABLE без обращения к порту. Результат пишется в журнал.
 */
class GrantSelfPermissions(
    private val caps: CapabilitiesSource,
    private val admin: DeviceAdmin,
    private val journal: CheckResultJournal,
    private val clock: Clock,
) {
    suspend operator fun invoke(): CheckResult {
        val result = if (caps.current().mode == AgentMode.DEVICE_OWNER) granted() else notDeviceOwner()
        journal.record(result)
        return result
    }

    private suspend fun granted(): CheckResult {
        val grants = admin.grantSelfPermissions()
        val denied = grants.filterValues { !it }.keys
        val (status, note) = when {
            grants.isEmpty() -> CheckStatus.LIMITED to "нет разрешений для выдачи"
            denied.isEmpty() -> CheckStatus.OK to null
            else -> CheckStatus.LIMITED to "не выдано: ${denied.joinToString()}"
        }
        return CheckResult(CheckId.D6, status, grants.mapValues { it.value.toString() }, note, clock.nowMillis())
    }

    private fun notDeviceOwner() = CheckResult(
        CheckId.D6,
        CheckStatus.NOT_APPLICABLE,
        emptyMap(),
        "requires_device_owner",
        clock.nowMillis(),
    )
}
