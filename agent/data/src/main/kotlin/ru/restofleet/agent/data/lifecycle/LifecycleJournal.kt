package ru.restofleet.agent.data.lifecycle

import ru.restofleet.shared.kernel.Clock
import java.io.File

/** Запись журнала жизненного цикла; [reason] есть у START и START_DENIED. */
data class LifecycleEntry(val kind: Kind, val ts: Long, val reason: String?) {
    enum class Kind(val tag: String) {
        BOOT("boot"),
        START("start"),
        START_DENIED("start_denied"),
        STOP("stop"),
    }
}

/**
 * Персистентный журнал жизненного цикла агента для проверок L2/L3 (и будущего события `agent_lifecycle`).
 * Формат строки: `kind|ts` либо `kind|ts|reason`.
 */
class LifecycleJournal(file: File, private val clock: Clock, maxLines: Int = DEFAULT_MAX_LINES) {
    private val store = LineFile(file, maxLines)

    fun recordBoot() = write(LifecycleEntry.Kind.BOOT, null)

    /** Только фактический старт цикла в этом процессе; повторный onStartCommand сюда не попадает. */
    fun recordStart(reason: String) = write(LifecycleEntry.Kind.START, reason)

    fun recordStartDenied(reason: String) = write(LifecycleEntry.Kind.START_DENIED, reason)

    fun recordStop() = write(LifecycleEntry.Kind.STOP, null)

    fun entries(): List<LifecycleEntry> = store.lines().mapNotNull(::parse)

    fun lastBootAt(): Long? = last(LifecycleEntry.Kind.BOOT)?.ts

    fun lastWatchdogStartAt(): Long? = last(LifecycleEntry.Kind.START, WATCHDOG)?.ts

    fun lastWatchdogDeniedAt(): Long? = last(LifecycleEntry.Kind.START_DENIED, WATCHDOG)?.ts

    private fun last(kind: LifecycleEntry.Kind, reason: String? = null): LifecycleEntry? =
        entries().lastOrNull { it.kind == kind && (reason == null || it.reason == reason) }

    private fun write(kind: LifecycleEntry.Kind, reason: String?) {
        val safe = reason?.map { if (it == SEPARATOR || it == '\n' || it == '\r') '_' else it }?.joinToString("")
        store.append(listOfNotNull(kind.tag, clock.nowMillis(), safe).joinToString(SEPARATOR.toString()))
    }

    private fun parse(line: String): LifecycleEntry? {
        val parts = line.split(SEPARATOR)
        val kind = LifecycleEntry.Kind.entries.firstOrNull { it.tag == parts.firstOrNull() }
        val ts = parts.getOrNull(1)?.toLongOrNull()
        val hasReason = kind == LifecycleEntry.Kind.START || kind == LifecycleEntry.Kind.START_DENIED
        val expectedParts = if (hasReason) PARTS_WITH_REASON else PARTS_WITHOUT_REASON
        return if (kind != null && ts != null && parts.size == expectedParts) {
            LifecycleEntry(kind, ts, parts.getOrNull(2))
        } else {
            null
        }
    }

    companion object {
        const val DEFAULT_MAX_LINES = 500
        const val WATCHDOG = "watchdog"
        private const val SEPARATOR = '|'
        private const val PARTS_WITH_REASON = 3
        private const val PARTS_WITHOUT_REASON = 2
    }
}
