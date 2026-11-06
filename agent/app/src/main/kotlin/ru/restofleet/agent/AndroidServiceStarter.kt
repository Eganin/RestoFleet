package ru.restofleet.agent

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import ru.restofleet.agent.data.lifecycle.LifecycleJournal
import ru.restofleet.agent.data.lifecycle.ServiceStarter

/**
 * Запуск [AgentService] через `startForegroundService`. На API 31+ запуск FGS из фона (в т.ч. из сторожа) может
 * быть запрещён системой: `ForegroundServiceStartNotAllowedException` журналируется как `start_denied`. Любой
 * другой сбой (`SecurityException`, иной `IllegalStateException`) — `start_failed(<класс>)`. Оба видны в L3 как
 * LIMITED и не роняют процесс. На API 26–30 `startForegroundService` из фона разрешён, этих исключений там нет.
 */
class AndroidServiceStarter(
    private val context: Context,
    private val journal: LifecycleJournal,
) : ServiceStarter {
    override fun ensureRunning(reason: String) {
        try {
            ContextCompat.startForegroundService(context, AgentService.intent(context, reason))
        } catch (e: IllegalStateException) {
            record(reason, e)
        } catch (e: SecurityException) {
            record(reason, e)
        }
    }

    // Журнал сам не бросает (best effort), поэтому запись внутри catch безопасна.
    private fun record(reason: String, e: RuntimeException) {
        if (isForegroundStartNotAllowed(e)) {
            journal.recordStartDenied(reason)
        } else {
            journal.recordStartFailed(reason, e::class.java.simpleName)
        }
        Log.w(TAG, "foreground start not performed (reason=$reason)", e)
    }

    private companion object {
        const val TAG = "ServiceStarter"
    }
}
