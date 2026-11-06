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
    Reading.Unavailable(UnavailableReason.PermissionMissing(permissionName(e)))
} catch (e: Exception) {
    Reading.Unavailable(UnavailableReason.Failed(e.message ?: e::class.simpleName.orEmpty()))
}

private val PERMISSION_REGEX = Regex("""android\.permission\.([A-Z_]+)""")

/** Короткое имя разрешения из сообщения SecurityException; если не нашлось — имя типа исключения. */
private fun permissionName(e: SecurityException): String =
    e.message?.let { PERMISSION_REGEX.find(it)?.groupValues?.get(1) } ?: "SecurityException"
