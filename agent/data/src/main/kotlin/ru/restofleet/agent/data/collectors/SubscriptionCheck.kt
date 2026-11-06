package ru.restofleet.agent.data.collectors

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.shared.kernel.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Проверка R12/R14: «подписка зарегистрирована + последнее событие за сессию».
 * Подписка стартует при создании; OK — подписка жива (даже если событий ещё не было), UNAVAILABLE — упала.
 */
class SubscriptionCheck<T>(
    override val id: CheckId,
    private val clock: Clock,
    events: Flow<T>,
    scope: CoroutineScope,
    private val describe: (T) -> String,
) : CapabilityCheck {
    @Volatile private var last: Pair<Long, T>? = null

    @Volatile private var count = 0

    @Volatile private var error: String? = null

    init {
        scope.launch {
            @Suppress("TooGenericExceptionCaught") // сбой подписки (SecurityException и т.п.) отражается в отчёте
            try {
                events.collect {
                    last = clock.nowMillis() to it
                    count++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName.orEmpty()
            }
        }
    }

    override suspend fun run(): CheckResult {
        val failure = error
        val event = last
        return when {
            failure != null -> result(CheckStatus.UNAVAILABLE, emptyMap(), "Failed: $failure")
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
}
