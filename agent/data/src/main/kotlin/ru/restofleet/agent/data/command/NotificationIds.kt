package ru.restofleet.agent.data.command

import ru.restofleet.shared.kernel.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Идентификаторы уведомлений команд (C1, C2): производные от времени и строго возрастающие внутри процесса.
 * Счётчик с константного начала после перезапуска процесса затёр бы ещё не прочитанные сообщения; время
 * между перезапусками уходит вперёд, поэтому новый процесс не повторяет id прежнего. Один экземпляр на процесс.
 */
class NotificationIds(private val clock: Clock) {
    private val last = AtomicLong(0)

    fun next(): Int = last.updateAndGet { max(it + 1, clock.nowMillis()) }.toInt() and Int.MAX_VALUE
}
