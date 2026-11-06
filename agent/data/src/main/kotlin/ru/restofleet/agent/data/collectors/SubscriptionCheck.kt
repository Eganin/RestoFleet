package ru.restofleet.agent.data.collectors

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.shared.kernel.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Проверка R12/R14: «подписка зарегистрирована + последнее событие за сессию».
 * Подписка стартует при создании. OK — только если collect реально запущен и ещё жив (событий может не быть);
 * после отмены/завершения/ошибки — UNAVAILABLE; если collect не стартовал за [startTimeoutMs] — NOT_CHECKED.
 */
class SubscriptionCheck<T>(
    override val id: CheckId,
    private val clock: Clock,
    events: Flow<T>,
    scope: CoroutineScope,
    private val startTimeoutMs: Long = DEFAULT_START_TIMEOUT_MS,
    private val describe: (T) -> String,
) : CapabilityCheck {
    private val started = CompletableDeferred<Unit>()

    @Volatile private var last: Pair<Long, T>? = null

    @Volatile private var count = 0

    @Volatile private var error: String? = null

    @Volatile private var finished = false

    init {
        scope.launch {
            @Suppress("TooGenericExceptionCaught") // сбой подписки (SecurityException и т.п.) отражается в отчёте
            try {
                events.onStart { started.complete(Unit) }.collect {
                    last = clock.nowMillis() to it
                    count++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName.orEmpty()
            }
        }.invokeOnCompletion {
            finished = true
            started.complete(Unit) // отмена до старта: run() не должен ждать таймаут
        }
    }

    override suspend fun run(): CheckResult {
        if (withTimeoutOrNull(startTimeoutMs) { started.await() } == null) {
            return result(CheckStatus.NOT_CHECKED, emptyMap(), "subscription not started yet")
        }
        val failure = error
        val event = last
        return when {
            failure != null -> result(CheckStatus.UNAVAILABLE, emptyMap(), "Failed: $failure")
            finished -> result(CheckStatus.UNAVAILABLE, emptyMap(), "Failed: subscription not active")
            event == null -> result(CheckStatus.OK, mapOf("events" to "0"), "subscription registered, no events yet")
            else -> result(
                CheckStatus.OK,
                mapOf(
                    "events" to count.toString(),
                    "last_event" to describe(event.second),
                    "last_at" to "${event.first}",
                ),
                null,
            )
        }
    }

    private fun result(status: CheckStatus, raw: Map<String, String>, note: String?) =
        CheckResult(id, status, raw, note, clock.nowMillis())

    private companion object {
        const val DEFAULT_START_TIMEOUT_MS = 2_000L
    }
}
