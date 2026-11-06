package ru.restofleet.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.restofleet.agent.data.lifecycle.AgentCycle
import ru.restofleet.agent.data.lifecycle.CpuWakeLock
import ru.restofleet.agent.data.lifecycle.LifecycleJournal
import ru.restofleet.agent.data.lifecycle.TelemetryLoop
import ru.restofleet.agent.di.AGENT_DISPATCHER

/**
 * Foreground-сервис (specialUse): держит цикл телеметрии. Тонкий: вся логика в `AgentCycle`/`TelemetryLoop`.
 * Koin-инъекция допустима: сервис создаёт система.
 */
class AgentService : LifecycleService(), KoinComponent {
    private val notifications: AgentNotifications by inject()
    private val loop: TelemetryLoop by inject()
    private val journal: LifecycleJournal by inject()
    private val starter: ForegroundStarter by inject()
    private val wakeLock: CpuWakeLock by inject()
    private val dispatcher: CoroutineDispatcher by inject(AGENT_DISPATCHER)
    private val cycle by lazy { AgentCycle(lifecycleScope, loop, journal, dispatcher, wakeLock) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // В первые секунды после startForegroundService, до любой другой работы.
        if (!enterForeground()) {
            // Система отказала: цикл не стартует, сервис останавливается; sticky-перезапуск повторил бы отказ.
            stopSelf()
            return START_NOT_STICKY
        }
        // Только запускает корутину на IO и возвращается: ни I/O журнала, ни тик не выполняются на главном потоке.
        cycle.start(intent?.getStringExtra(EXTRA_REASON) ?: REASON_SYSTEM_RESTART)
        return START_STICKY
    }

    // Ловим только вокруг startForeground: ForegroundServiceStartNotAllowedException (API 31+, это
    // IllegalStateException), SecurityException, а на API 34+ ещё и Missing/InvalidForegroundServiceTypeException
    // (IllegalArgumentException).
    private fun enterForeground(): Boolean {
        val notification = notifications.build()
        return try {
            starter.start(this, AgentNotifications.NOTIFICATION_ID, notification)
            true
        } catch (e: IllegalStateException) {
            recordRefused(e)
        } catch (e: SecurityException) {
            recordRefused(e)
        } catch (e: IllegalArgumentException) {
            recordRefused(e)
        }
    }

    // Журнал сам не бросает (best effort), поэтому запись внутри catch безопасна.
    private fun recordRefused(e: RuntimeException): Boolean {
        if (isForegroundStartNotAllowed(e)) {
            journal.recordStartDenied(REASON_FOREGROUND_NOT_ALLOWED)
        } else {
            journal.recordStartFailed(REASON_FOREGROUND_FAILED, e::class.java.simpleName)
        }
        Log.w(TAG, "startForeground refused", e)
        return false
    }

    override fun onDestroy() {
        cycle.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REASON = "reason"
        const val REASON_SYSTEM_RESTART = "system_restart"
        const val REASON_FOREGROUND_NOT_ALLOWED = "foreground_not_allowed"
        const val REASON_FOREGROUND_FAILED = "foreground_failed"
        private const val TAG = "AgentService"

        fun intent(context: Context, reason: String): Intent =
            Intent(context, AgentService::class.java).putExtra(EXTRA_REASON, reason)
    }
}
