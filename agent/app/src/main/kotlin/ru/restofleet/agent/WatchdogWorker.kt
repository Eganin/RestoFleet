package ru.restofleet.agent

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.restofleet.agent.data.lifecycle.ServiceStarter
import ru.restofleet.agent.data.lifecycle.WatchdogSchedule
import java.util.concurrent.TimeUnit

/** Сторож: раз в 15 минут проверяет, что сервис жив, и поднимает его при необходимости. */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters,
    private val starter: ServiceStarter,
) : Worker(context, params) {
    override fun doWork(): Result {
        starter.ensureRunning(REASON)
        return Result.success()
    }

    companion object {
        const val REASON = "watchdog"
    }
}

/** Планирование сторожа: уникальная периодическая работа, повторный вызов сохраняет существующую (KEEP). */
object WatchdogScheduler {
    const val WORK_NAME = "agent_watchdog"
    const val PERIOD_MINUTES = 15L

    fun schedule(workManager: WorkManager) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .addTag(WatchdogWorker::class.java.name)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Сторож считается запланированным, если у уникальной работы есть не завершённый запрос (ENQUEUED/RUNNING/BLOCKED). */
class WorkManagerWatchdogSchedule(private val workManager: WorkManager) : WatchdogSchedule {
    override suspend fun isScheduled(): Boolean = withContext(Dispatchers.IO) {
        workManager.getWorkInfosForUniqueWork(WatchdogScheduler.WORK_NAME).get().any {
            it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.BLOCKED
        }
    }
}
