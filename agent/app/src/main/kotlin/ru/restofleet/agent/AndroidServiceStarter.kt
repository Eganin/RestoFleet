package ru.restofleet.agent

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import ru.restofleet.agent.data.lifecycle.LifecycleJournal
import ru.restofleet.agent.data.lifecycle.ServiceStarter

/**
 * Запуск [AgentService] через `startForegroundService`. На API 31+ запуск из фона (в т.ч. из сторожа) может быть
 * запрещён системой (`ForegroundServiceStartNotAllowedException` — подкласс `IllegalStateException`; на API 26–30
 * фоновый запуск бросает его же): отказ журналируется для L3 и не роняет процесс.
 */
class AndroidServiceStarter(
    private val context: Context,
    private val journal: LifecycleJournal,
) : ServiceStarter {
    override fun ensureRunning(reason: String) {
        try {
            ContextCompat.startForegroundService(context, AgentService.intent(context, reason))
        } catch (e: IllegalStateException) {
            journal.recordStartDenied(reason)
            Log.w(TAG, "foreground start denied (reason=$reason)", e)
        }
    }

    private companion object {
        const val TAG = "ServiceStarter"
    }
}
