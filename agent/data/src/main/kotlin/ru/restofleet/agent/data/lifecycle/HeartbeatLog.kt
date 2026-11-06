package ru.restofleet.agent.data.lifecycle

import ru.restofleet.shared.kernel.Clock
import java.io.File

/** Журнал меток живости агента (L1): по одной метке epoch millis на каждый тик телеметрии. */
class HeartbeatLog(file: File, private val clock: Clock, private val maxLines: Int = DEFAULT_MAX_LINES) {
    private val store = LineFile(file, maxLines)

    fun append() {
        store.append(clock.nowMillis().toString())
    }

    /** Метки по возрастанию записи; не более [maxLines] последних, нечитаемые строки пропускаются. */
    fun timestamps(): List<Long> = store.lines().mapNotNull { it.trim().toLongOrNull() }.takeLast(maxLines)

    companion object {
        const val DEFAULT_MAX_LINES = 5_000
    }
}
