package ru.restofleet.agent.data.collectors

import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.shared.kernel.Clock
import kotlin.coroutines.cancellation.CancellationException

/** Проверка Lab поверх считывания: статус выводится из [Reading], [raw] превращает значение в сырые поля отчёта. */
class ReadingCheck<T>(
    override val id: CheckId,
    private val clock: Clock,
    private val read: suspend () -> Reading<T>,
    private val raw: (T) -> Map<String, String>,
) : CapabilityCheck {

    @Suppress("TooGenericExceptionCaught") // коллектор не должен ронять прогон Lab
    override suspend fun run(): CheckResult {
        val reading = try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Reading.Unavailable(UnavailableReason.Failed(e.message ?: e::class.simpleName.orEmpty()))
        }
        return when (reading) {
            is Reading.Available -> result(CheckStatus.OK, raw(reading.value), null)
            is Reading.Unavailable -> result(statusOf(reading.reason), emptyMap(), describe(reading.reason))
        }
    }

    private fun result(status: CheckStatus, raw: Map<String, String>, note: String?) =
        CheckResult(id, status, raw, note, clock.nowMillis())

    private fun statusOf(reason: UnavailableReason): CheckStatus = when (reason) {
        UnavailableReason.EmulatorStub -> CheckStatus.LIMITED
        is UnavailableReason.ApiLevelTooLow, UnavailableReason.RequiresDeviceOwner -> CheckStatus.NOT_APPLICABLE
        is UnavailableReason.PermissionMissing, UnavailableReason.NoSensor, is UnavailableReason.Failed ->
            CheckStatus.UNAVAILABLE
    }

    private fun describe(reason: UnavailableReason): String = when (reason) {
        is UnavailableReason.ApiLevelTooLow ->
            "ApiLevelTooLow: requires API ${reason.required}, actual ${reason.actual}"
        is UnavailableReason.PermissionMissing -> "PermissionMissing: ${reason.permission}"
        is UnavailableReason.Failed -> "Failed: ${reason.message}"
        UnavailableReason.NoSensor -> "NoSensor"
        UnavailableReason.RequiresDeviceOwner -> "RequiresDeviceOwner"
        UnavailableReason.EmulatorStub -> "EmulatorStub"
    }
}
