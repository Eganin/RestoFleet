package ru.restofleet.agent.data.probes

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.port.ProbeExecutor
import ru.restofleet.shared.kernel.Clock
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTPS-проба на OkHttp. Таймаут — только `callTimeout` клиента (внутренних `withTimeout` нет, чтобы не рвать
 * прогон Lab); отмена корутины отменяет вызов. Не бросает: сбои превращаются в [ProbeResult] с классом ошибки,
 * [CancellationException] и [Error] пробрасываются. Редиректы не выполняются, ретраи выключены, кэша и пула
 * соединений нет.
 * Доверие сертификатам — у [baseClient] (в продакшене системное хранилище). Успех по HTTP-коду — с учётом
 * `expectedStatus` цели (реш. 219).
 */
class OkHttpProbeExecutor(
    private val baseClient: OkHttpClient,
    private val clock: Clock,
    private val networkAvailable: () -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
) : ProbeExecutor {

    @Suppress("TooGenericExceptionCaught") // проба не должна ронять прогон; Error и отмена не перехватываются
    override suspend fun probe(target: ProbeTarget): ProbeResult {
        val listener = PhaseTimingListener(nanoTime)
        val started = nanoTime()
        val outcome: Result<Int> = try {
            val timeout = Duration.ofMillis(target.timeoutMs)
            val client = baseClient.newBuilder()
                .callTimeout(timeout)
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .cache(null)
                // Холодное соединение на каждую пробу: иначе у повторной пробы нет фаз DNS/TCP/TLS.
                .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
                .eventListenerFactory { listener }
                .build()
            Result.success(execute(client.newCall(Request.Builder().url(target.url).build())))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        val totalMs = (nanoTime() - started) / NANOS_IN_MS
        val status = outcome.getOrNull()
        val error = if (status != null) {
            ProbeErrorClassifier.fromStatus(status, target.expectedStatus)
        } else {
            ProbeErrorClassifier.fromThrowable(outcome.exceptionOrNull()!!, isNetworkAvailable())
        }
        return ProbeResult(
            targetId = target.id,
            ok = status != null && error == null,
            httpStatus = status,
            error = error,
            totalMs = totalMs,
            phases = listener.phases(),
            ts = clock.nowMillis(),
        )
    }

    /** Сбой проверки сети не ломает пробу: считаем сеть доступной, класс ошибки определит реальный сбой. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun isNetworkAvailable(): Boolean = try {
        networkAvailable()
    } catch (e: Exception) {
        true
    }

    private suspend fun execute(call: Call): Int = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response.use { it.code })
            }
        })
    }

    private companion object {
        const val NANOS_IN_MS = 1_000_000L
    }
}
