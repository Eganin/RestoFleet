package ru.restofleet.server.data.mqtt

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.restofleet.server.data.ingest.IngestPipeline
import ru.restofleet.shared.protocol.SharedSubscriptions

/**
 * Жизненный цикл MQTT-части бэкенда для `server:app`: [start] — подключение (с повторами, пока брокер не ответит),
 * подписки `$share/backend/rf/v1/dev/+/…` и ответов DynSec, запуск конвейера ingest; [stop] — остановка конвейера
 * без подтверждения текущей пачки и штатное отключение (сессия на брокере сохраняется 24 ч).
 *
 * Отказ брокера в подписке (нет прав у роли `backend-service`) — исключение в задаче [start]: это ошибка конфигурации
 * стенда, `server:app` должен завершиться, а не работать без ingest. [isReady] — подписки оформлены (для `/health`).
 */
class MqttBackend(
    private val client: HiveMqClient,
    private val pipeline: IngestPipeline,
) {
    private val log = KotlinLogging.logger {}
    private var job: Job? = null
    private val ready = MutableStateFlow(false)

    /** Подключён и подписан: ingest принимает сообщения. */
    val isReady: Boolean get() = ready.value && client.isConnected

    init {
        // До подключения: брокер сразу после CONNACK досылает накопленное в сессии
        client.onDeviceMessage(pipeline::offer)
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        var pause = INITIAL_RETRY_MS
        while (true) {
            try {
                client.connect()
                break
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                log.warn { "MQTT connect failed (${e.javaClass.simpleName}), retry in $pause ms" }
                delay(pause)
                pause = (pause * 2).coerceAtMost(MAX_RETRY_MS)
            }
        }
        client.subscribe(SharedSubscriptions.backend() + DynSecBrokerAccounts.RESPONSE_TOPIC)
        ready.value = true
        log.info { "MQTT backend subscribed, ingest started" }
        pipeline.run()
    }.also { job = it }

    /** Ждёт, пока [start] оформит подписки. */
    suspend fun awaitReady() {
        ready.first { it }
    }

    suspend fun stop() {
        ready.value = false
        job?.cancel()
        job?.join()
        client.disconnect()
    }

    private companion object {
        const val INITIAL_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 30_000L
    }
}
