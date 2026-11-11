package ru.restofleet.server.app.serve

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Периодическая задача бэкенда: [action] возвращает число затронутых записей (для лога). */
class PeriodicJob(val name: String, val periodMillis: Long, val action: suspend () -> Int) {
    init {
        require(periodMillis > 0) { "period of $name must be positive" }
    }
}

private val log = KotlinLogging.logger("ru.restofleet.server.app.Jobs")

/**
 * Запуск [job] каждые [PeriodicJob.periodMillis] (контракт КМ2b: `ExpireCommands`, `MarkStalePollers` — 5 с). Сбой
 * одного прогона (БД недоступна) логируется классом исключения и не роняет цикл; отмена scope — останавливает.
 */
fun CoroutineScope.launchPeriodic(job: PeriodicJob): Job = launch {
    while (isActive) {
        delay(job.periodMillis)
        try {
            val changed = job.action()
            if (changed > 0) log.info { "${job.name}: $changed" }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            log.warn { "${job.name} failed: ${e.javaClass.simpleName}" }
        }
    }
}

/** Жизненный цикл приёма MQTT (адаптер над `MqttBackend` из `server:data`). */
interface IngestLifecycle {
    /** Подключение с повторами, подписки, конвейер; задача завершается только ошибкой конфигурации или отменой. */
    fun start(scope: CoroutineScope): Job

    /** Конвейер без подтверждения текущей пачки (она придёт снова), затем штатное отключение. */
    suspend fun stop()
}

/**
 * Фоновая часть `serve`: ingest и периодические задачи в своём scope. Порядок остановки (контракт КМ2b): сначала
 * ingest (`stop()` — неподтверждённая пачка останется у брокера), затем задачи; `koin.close()` (пул БД) — после этого,
 * в composition root. Падение ingest (брокер отказал в подписке — ошибка конфигурации стенда) — [onFatal]: процесс
 * должен завершиться, а не работать без приёма.
 */
class BackendRuntime(
    private val scope: CoroutineScope,
    private val ingest: IngestLifecycle,
    private val jobs: List<PeriodicJob>,
    private val onFatal: (Throwable) -> Unit,
) {
    private var ingestJob: Job? = null
    private val jobHandles = mutableListOf<Job>()

    fun start() {
        ingestJob = ingest.start(scope).also { job ->
            job.invokeOnCompletion { cause ->
                if (cause != null && cause !is CancellationException) onFatal(cause)
            }
        }
        jobs.forEach { jobHandles += scope.launchPeriodic(it) }
    }

    suspend fun stop() {
        ingest.stop()
        jobHandles.forEach { it.cancel() }
        jobHandles.forEach { it.join() }
        ingestJob?.join()
    }
}
