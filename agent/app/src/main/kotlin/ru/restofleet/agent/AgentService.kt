package ru.restofleet.agent

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ru.restofleet.agent.data.lifecycle.AgentCycle
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
    private val dispatcher: CoroutineDispatcher by inject(AGENT_DISPATCHER)
    private val cycle by lazy { AgentCycle(lifecycleScope, loop, journal, dispatcher) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // В первые секунды после startForegroundService, до любой другой работы.
        ServiceCompat.startForeground(
            this,
            AgentNotifications.NOTIFICATION_ID,
            notifications.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        // Только запускает корутину на IO и возвращается: ни I/O журнала, ни тик не выполняются на главном потоке.
        cycle.start(intent?.getStringExtra(EXTRA_REASON) ?: REASON_SYSTEM_RESTART)
        return START_STICKY
    }

    override fun onDestroy() {
        cycle.stop()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REASON = "reason"
        const val REASON_SYSTEM_RESTART = "system_restart"

        fun intent(context: Context, reason: String): Intent =
            Intent(context, AgentService::class.java).putExtra(EXTRA_REASON, reason)
    }
}
