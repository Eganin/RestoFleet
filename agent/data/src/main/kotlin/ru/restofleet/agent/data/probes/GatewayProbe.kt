package ru.restofleet.agent.data.probes

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.agent.domain.model.ProbePhases
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.SystemClock
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.cancellation.CancellationException

/**
 * P3: TCP-соединение со шлюзом по умолчанию активной сети (данные не отправляются). Ошибка `EPERM` при connect —
 * признак запрета локальной сети ([ProbeError.LOCAL_NETWORK_DENIED]). Таймаут — только параметр `connect`,
 * внутренних `withTimeout` нет. Не бросает; [CancellationException] и [Error] пробрасываются, отмена закрывает сокет.
 */
class GatewayProbe(
    private val context: Context,
    private val socketFactory: () -> Socket = ::Socket,
    private val clock: Clock = SystemClock,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    @Suppress("TooGenericExceptionCaught") // проба не должна ронять прогон; Error и отмена не перехватываются
    suspend fun probe(port: Int = DEFAULT_PORT, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ProbeResult {
        val started = nanoTime()
        val failure: Throwable? = try {
            val gateway = defaultGateway()
            if (gateway == null) {
                return result(ProbeError.NO_NETWORK, started)
            }
            connect(InetSocketAddress(gateway, port), timeoutMs)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        // Шлюз найден, значит сеть есть: для классификации networkAvailable = true.
        return result(failure?.let { ProbeErrorClassifier.fromThrowable(it, networkAvailable = true) }, started)
    }

    private fun result(error: ProbeError?, started: Long): ProbeResult {
        val totalMs = (nanoTime() - started) / NANOS_IN_MS
        return ProbeResult(
            targetId = TARGET_ID,
            ok = error == null,
            httpStatus = null,
            error = error,
            totalMs = totalMs,
            phases = ProbePhases(null, totalMs.takeIf { error == null }, null, null),
            ts = clock.nowMillis(),
        )
    }

    private fun defaultGateway(): InetAddress? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val link = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null
        return GatewayResolver.pick(link.routes.map { RouteCandidate(it.isDefaultRoute, it.gateway) })
    }

    /** Блокирующий connect уходит в IO; при отмене сокет закрывается, и connect возвращается с ошибкой. */
    private suspend fun connect(address: InetSocketAddress, timeoutMs: Int) {
        socketFactory().use { socket ->
            coroutineScope {
                val connecting = async(Dispatchers.IO) { socket.connect(address, timeoutMs) }
                try {
                    connecting.await()
                } catch (e: CancellationException) {
                    socket.close()
                    throw e
                }
            }
        }
    }

    private companion object {
        const val TARGET_ID = "gateway"
        const val DEFAULT_PORT = 80
        const val DEFAULT_TIMEOUT_MS = 3_000
        const val NANOS_IN_MS = 1_000_000L
    }
}
