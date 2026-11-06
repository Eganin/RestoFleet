package ru.restofleet.agent.data.lifecycle

import android.content.Context
import ru.restofleet.shared.kernel.Clock
import java.io.File

/** Интервал тика телеметрии (P7: keepalive 30 с). */
const val TELEMETRY_INTERVAL_MS = 30_000L

/**
 * Расположение файлов жизненного цикла. Живут в `noBackupFilesDir`: они не должны попадать в резервные копии
 * и переноситься на другое устройство.
 */
class LifecycleStorage(private val dir: File, private val clock: Clock) {
    fun heartbeatLog() = HeartbeatLog(File(dir, HEARTBEAT_FILE), clock)

    fun journal() = LifecycleJournal(File(dir, JOURNAL_FILE), clock)

    companion object {
        const val HEARTBEAT_FILE = "heartbeat.log"
        const val JOURNAL_FILE = "lifecycle.log"

        fun forContext(context: Context, clock: Clock) = LifecycleStorage(context.noBackupFilesDir, clock)
    }
}
