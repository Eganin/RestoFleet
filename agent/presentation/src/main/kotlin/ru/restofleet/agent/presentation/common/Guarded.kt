package ru.restofleet.agent.presentation.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Сбой use case не должен ни зависнуть в флагах, ни уронить процесс (он общий с сервисом агента): ошибка уходит
 * в [onError], результат — `null`. CancellationException при активном scope (чужой таймаут, отменённый future) —
 * тоже сбой; отмена самого scope (VM уничтожена) пробрасывается. [Error] не перехватывается.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> guarded(onError: (Exception) -> Unit, block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        onError(e)
        null
    } catch (e: Exception) {
        onError(e)
        null
    }
