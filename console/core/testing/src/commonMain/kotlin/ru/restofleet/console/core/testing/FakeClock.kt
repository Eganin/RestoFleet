package ru.restofleet.console.core.testing

import ru.restofleet.shared.kernel.Clock

/** Управляемые часы для тестов. */
class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now

    fun advance(ms: Long) {
        now += ms
    }
}
