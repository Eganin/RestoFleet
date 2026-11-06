package ru.restofleet.agent.data.lifecycle

import android.util.Log
import ru.restofleet.shared.kernel.Clock
import java.io.File
import java.io.IOException

/** Запись журнала жизненного цикла; [reason] есть у START, START_DENIED и START_FAILED. */
data class LifecycleEntry(val kind: Kind, val ts: Long, val reason: String?) {
    enum class Kind(val tag: String) {
        BOOT("boot"),
        START("start"),
        START_DENIED("start_denied"),
        START_FAILED("start_failed"),
        STOP("stop"),
    }
}

/**
 * Персистентный журнал жизненного цикла агента для проверок L2/L3 (и будущего события `agent_lifecycle`).
 * Формат строки: `kind|ts` либо `kind|ts|reason`.
 */
@Suppress("TooManyFunctions") // записи и запросы одного журнала
class LifecycleJournal(
    file: File,
    private val clock: Clock,
    maxLines: Int = DEFAULT_MAX_LINES,
    private val onError: (IOException) -> Unit = { Log.w(TAG, "journal write failed", it) },
) {
    // Последняя запись boot не вытесняется потоком других записей (иначе L2 теряет boot).
    private val store = LineFile(file, maxLines) { it.startsWith("${LifecycleEntry.Kind.BOOT.tag}$SEPARATOR") }

    fun recordBoot() = write(LifecycleEntry.Kind.BOOT, null)

    /** Только фактический старт цикла в этом процессе; повторный onStartCommand сюда не попадает. */
    fun recordStart(reason: String) = write(LifecycleEntry.Kind.START, reason)

    fun recordStartDenied(reason: String) = write(LifecycleEntry.Kind.START_DENIED, reason)

    /** Запуск сорвался не из-за запрета фонового старта (SecurityException и т.п.); [cause] — класс исключения. */
    fun recordStartFailed(reason: String, cause: String) =
        write(LifecycleEntry.Kind.START_FAILED, "$reason:$cause")

    fun recordStop() = write(LifecycleEntry.Kind.STOP, null)

    fun entries(): List<LifecycleEntry> = store.lines().mapNotNull(::parse)

    fun lastBootAt(): Long? = last(LifecycleEntry.Kind.BOOT)?.ts

    /** Последний фактический старт цикла, любая причина. */
    fun lastStartAt(): Long? = last(LifecycleEntry.Kind.START)?.ts

    /** Последний срыв запуска: запрет фонового старта или иная ошибка, любая причина. */
    fun lastStartFailureAt(): Long? = entries()
        .lastOrNull { it.kind == LifecycleEntry.Kind.START_DENIED || it.kind == LifecycleEntry.Kind.START_FAILED }?.ts

    fun lastWatchdogStartAt(): Long? = last(LifecycleEntry.Kind.START, WATCHDOG)?.ts

    fun lastWatchdogDeniedAt(): Long? = last(LifecycleEntry.Kind.START_DENIED, WATCHDOG)?.ts

    private fun last(kind: LifecycleEntry.Kind, reason: String? = null): LifecycleEntry? =
        entries().lastOrNull { it.kind == kind && (reason == null || it.reason == reason) }

    /** Журнал вторичен по отношению к запуску агента: сбой записи не пробрасывается, а передаётся в [onError]. */
    private fun write(kind: LifecycleEntry.Kind, reason: String?) {
        val safe = reason?.map { if (it == SEPARATOR || it == '\n' || it == '\r') '_' else it }?.joinToString("")
        try {
            store.append(listOfNotNull(kind.tag, clock.nowMillis(), safe).joinToString(SEPARATOR.toString()))
        } catch (e: IOException) {
            onError(e)
        }
    }

    private fun parse(line: String): LifecycleEntry? {
        val parts = line.split(SEPARATOR)
        val kind = LifecycleEntry.Kind.entries.firstOrNull { it.tag == parts.firstOrNull() }
        val ts = parts.getOrNull(1)?.toLongOrNull()
        val hasReason = kind != null && kind != LifecycleEntry.Kind.BOOT && kind != LifecycleEntry.Kind.STOP
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
        private const val TAG = "LifecycleJournal"
        private const val SEPARATOR = '|'
        private const val PARTS_WITH_REASON = 3
        private const val PARTS_WITHOUT_REASON = 2
    }
}
