package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.withTimeoutOrNull
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.shared.kernel.Clock
import kotlin.coroutines.cancellation.CancellationException

/** Запускает проверки последовательно; сбой, зависание или отсутствие проверки не влияют на остальные. */
class RunCapabilityChecks(
    checks: List<CapabilityCheck>,
    private val clock: Clock,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val checks: Map<CheckId, CapabilityCheck> = checks.associateBy { it.id }

    init {
        require(this.checks.size == checks.size) {
            "Duplicate checks: ${checks.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys}"
        }
    }

    /** [ids] = null — все проверки каталога; результат упорядочен по [CheckId]. */
    suspend operator fun invoke(ids: Set<CheckId>? = null): List<CheckResult> =
        CheckId.entries
            .filter { ids == null || it in ids }
            .map { id -> checks[id]?.let { execute(it) } ?: synthetic(id, CheckStatus.NOT_CHECKED, null) }

    // Ловим именно Exception: Error — состояние процесса, пробрасывается; CancellationException — тоже.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun execute(check: CapabilityCheck): CheckResult =
        try {
            withTimeoutOrNull(timeoutMillis) { check.run() }
                ?: synthetic(check.id, CheckStatus.UNAVAILABLE, "timeout $timeoutMillis ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synthetic(check.id, CheckStatus.UNAVAILABLE, e.message ?: e::class.simpleName.orEmpty())
        }

    private fun synthetic(id: CheckId, status: CheckStatus, note: String?) =
        CheckResult(id, status, emptyMap(), note, clock.nowMillis())

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    }
}
