package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.agent.domain.port.CheckResultJournal
import ru.restofleet.agent.domain.port.DeviceAdmin
import ru.restofleet.shared.kernel.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * D6: самовыдача разрешений (только Device Owner). OK — выданы все, LIMITED — часть не выдана (`note` — код
 * `not_granted:<имена>` или `nothing_to_grant`, текст — в presentation); `raw` — карта `разрешение -> true/false`.
 * Сбой порта изолируется по канону 78: `exception:<Класс>`, `Error` и настоящая отмена вызывающего
 * пробрасываются. Не Device Owner — NOT_APPLICABLE без обращения к порту. Результат пишется в журнал.
 */
class GrantSelfPermissions(
    private val caps: CapabilitiesSource,
    private val admin: DeviceAdmin,
    private val journal: CheckResultJournal,
    private val clock: Clock,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(): CheckResult {
        val result = try {
            if (caps.current().mode == AgentMode.DEVICE_OWNER) granted() else notDeviceOwner()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            failed(e)
        } catch (e: Exception) {
            failed(e)
        }
        journal.record(result)
        return result
    }

    private fun failed(e: Exception) = CheckResult(
        CheckId.D6,
        CheckStatus.UNAVAILABLE,
        emptyMap(),
        "exception:${e::class.simpleName ?: "Unknown"}",
        clock.nowMillis(),
    )

    private suspend fun granted(): CheckResult {
        val grants = admin.grantSelfPermissions()
        val denied = grants.filterValues { !it }.keys
        val (status, note) = when {
            grants.isEmpty() -> CheckStatus.LIMITED to "nothing_to_grant"
            denied.isEmpty() -> CheckStatus.OK to null
            else -> CheckStatus.LIMITED to "not_granted:${denied.joinToString(",")}"
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
