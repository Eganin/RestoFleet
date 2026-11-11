package ru.restofleet.server.api.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.plugins.ratelimit.RateLimiter
import io.ktor.util.AttributeKey
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Счётчик неудачных Basic-проверок устройств на клиента (R-7, реш. 306): окно фиксированной длины [periodMillis],
 * не больше [limit] неудач. В отличие от корзины токенов Ktor, тратится не каждым запросом, а только ответом 401
 * ([recordFailure] из плагина [authFailureCounter]); лимит `poll-auth` ([limiter]) лишь читает состояние и отвечает
 * 429 до проверки пароля, поэтому подбор пароля к известному `deviceId` не тратит bcrypt сверх предела.
 */
class AuthFailureBudget(
    private val limit: Int,
    private val periodMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Window(val startedAt: Long, val failures: Int)

    private val windows = ConcurrentHashMap<Any, Window>()

    fun recordFailure(key: Any) {
        val at = now()
        if (windows.size >= MAX_KEYS) windows.entries.removeIf { at - it.value.startedAt >= periodMillis }
        windows.compute(key) { _, window ->
            if (window == null || at - window.startedAt >= periodMillis) {
                Window(at, 1)
            } else {
                Window(window.startedAt, window.failures + 1)
            }
        }
    }

    fun state(key: Any): RateLimiter.State {
        val at = now()
        val window = windows[key]?.takeIf { at - it.startedAt < periodMillis }
            ?: return RateLimiter.State.Available(limit, limit, at + periodMillis)
        val refillAt = window.startedAt + periodMillis
        return if (window.failures >= limit) {
            RateLimiter.State.Exhausted((refillAt - at).milliseconds)
        } else {
            RateLimiter.State.Available(limit - window.failures, limit, refillAt)
        }
    }

    /** Представление для плагина RateLimit Ktor: запрос ничего не тратит, только проверяет окно ключа. */
    fun limiter(key: Any): RateLimiter = object : RateLimiter {
        override suspend fun tryConsume(tokens: Int): RateLimiter.State = state(key)
    }

    private companion object {
        /** Выше — при записи выбрасываются истёкшие окна (память не растёт от перебора адресов). */
        const val MAX_KEYS = 10_000
    }
}

/** Вызов уже учтён: StatusPages переписывает ответ 401 вложенной отправкой, и `ResponseSent` срабатывает дважды. */
private val failureRecorded = AttributeKey<Unit>("AuthFailureRecorded")

/** Плагин маршрутов устройства: ответ 401 записывается в [AuthFailureBudget] по ключу клиента [key], раз на вызов. */
internal fun authFailureCounter(budget: AuthFailureBudget, key: (ApplicationCall) -> Any) =
    createRouteScopedPlugin("AuthFailureCounter") {
        on(ResponseSent) { call ->
            if (call.response.status() == HttpStatusCode.Unauthorized && !call.attributes.contains(failureRecorded)) {
                call.attributes.put(failureRecorded, Unit)
                budget.recordFailure(key(call))
            }
        }
    }
