package ru.restofleet.shared.kernel

/** Порт времени: use cases получают его через конструктор и детерминированно тестируются. Время — epoch millis (как в протоколе). */
fun interface Clock {
    fun nowMillis(): Long
}

/** Системные часы. */
object SystemClock : Clock {
    override fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}
