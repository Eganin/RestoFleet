package ru.restofleet.agent.data.collectors

import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason
import kotlin.coroutines.cancellation.CancellationException

/** Изоляция сбоев: коллектор не бросает, исключения Android превращаются в [Reading.Unavailable]. */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> guarded(block: () -> Reading<T>): Reading<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: SecurityException) {
    Reading.Unavailable(UnavailableReason.PermissionMissing(e.message ?: "unknown"))
} catch (e: Exception) {
    Reading.Unavailable(UnavailableReason.Failed(e.message ?: e::class.simpleName.orEmpty()))
}
