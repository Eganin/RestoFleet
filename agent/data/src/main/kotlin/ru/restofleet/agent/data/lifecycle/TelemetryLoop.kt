package ru.restofleet.agent.data.lifecycle

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import ru.restofleet.agent.domain.usecase.CollectTelemetry
import kotlin.coroutines.cancellation.CancellationException

/** Один тик: снимок телеметрии → лог → метка heartbeat (L1). Метка ставится, только если сбор вернул снимок. */
class TelemetryTick(
    private val collect: CollectTelemetry,
    private val heartbeat: HeartbeatLog,
    private val watched: List<String>,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    suspend operator fun invoke() {
        val snapshot = collect(watched)
        log(
            "tick seq=${snapshot.seq} ts=${snapshot.ts} " +
                "unavailable=${snapshot.unavailable.keys.sorted()}",
        )
        heartbeat.append()
    }

    private companion object {
        const val TAG = "AgentTick"
    }
}

/**
 * Бесконечный цикл с интервалом [intervalMs]; сбой тика (Exception) не останавливает цикл.
 * Перед каждым тиком продлевает [wakeLock] на [WAKE_LOCK_TIMEOUT_MS].
 */
class TelemetryLoop(
    private val intervalMs: Long,
    private val tick: suspend () -> Unit,
    private val wakeLock: CpuWakeLock,
    private val onError: (Exception) -> Unit = { Log.w(TAG, "tick failed", it) },
) {
    suspend fun run() {
        try {
            while (true) {
                wakeLock.hold(WAKE_LOCK_TIMEOUT_MS)
                runTick()
                delay(intervalMs)
            }
        } finally {
            // Любой выход (отмена, Error) отпускает CPU, в том числе если stop() отпустил его раньше последнего hold.
            wakeLock.release()
        }
    }

    @Suppress("TooGenericExceptionCaught") // изоляция тика; отмена цикла и Error пробрасываются
    private suspend fun runTick() {
        try {
            tick()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // настоящая отмена цикла — наружу
            onError(e) // иначе бродячий CE (чужой таймаут, отменённый future) — сбой тика
        } catch (e: Exception) {
            onError(e)
        }
    }

    companion object {
        /** Таймаут удержания CPU: 10 мин, около 20 интервалов; зависший цикл не держит CPU дольше. */
        const val WAKE_LOCK_TIMEOUT_MS = 600_000L
        private const val TAG = "AgentLoop"
    }
}

/**
 * Владеет заданием цикла: старт журналируется, только если цикл действительно запущен, а не уже работал.
 *
 * Весь цикл (включая запись журнала и heartbeat — файловый I/O) выполняется на [dispatcher], а не на потоке
 * вызывающего: [start] только запускает корутину и сразу возвращается (в проде — `Dispatchers.IO`).
 * Пока цикл работает, держит [wakeLock] (берёт в [start], отпускает в [stop]; продлевает сам цикл на каждом тике).
 */
class AgentCycle(
    private val scope: CoroutineScope,
    private val loop: TelemetryLoop,
    private val journal: LifecycleJournal,
    private val dispatcher: CoroutineDispatcher,
    private val wakeLock: CpuWakeLock,
) {
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** @return true, если цикл запущен этим вызовом. */
    @Synchronized
    fun start(reason: String): Boolean {
        if (isRunning) return false
        // Синхронно, до запуска корутины: CPU не должен уснуть между вызовом и первым тиком.
        wakeLock.hold(TelemetryLoop.WAKE_LOCK_TIMEOUT_MS)
        job = scope.launch(dispatcher) {
            journal.recordStart(reason)
            loop.run()
        }
        return true
    }

    @Synchronized
    fun stop() {
        val running = job ?: return
        running.cancel()
        job = null
        wakeLock.release()
        journal.recordStop()
    }
}
