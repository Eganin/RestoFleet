package ru.restofleet.server.simulator.data.probe

import okhttp3.OkHttpClient
import okhttp3.Request
import ru.restofleet.server.simulator.data.rest.send
import ru.restofleet.server.simulator.domain.model.ProbeResult
import ru.restofleet.server.simulator.domain.model.ProbeTarget
import ru.restofleet.server.simulator.domain.port.ProbeRunner
import ru.restofleet.server.simulator.domain.port.Ticker
import ru.restofleet.shared.probes.ProbeErrorClass
import ru.restofleet.shared.probes.classifyHttpStatus
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.time.Duration
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Проверки доступности `real`-режима (арх. разд. 10, ТЗ 7.2): `http` — GET без редиректов с таймаутом цели, успех по
 * [classifyHttpStatus] (`expectedStatus`, реш. 219); `tcp` — установка соединения с `host:port` из URL. Класс ошибки —
 * [ProbeErrorClass] из `shared:probes` (те же коды, что у агента).
 */
class HttpProbeRunner(private val base: OkHttpClient, private val ticker: Ticker) : ProbeRunner {
    override suspend fun run(target: ProbeTarget): ProbeResult {
        val started = ticker.nanos()
        val (status, error) = try {
            if (target.type == TCP) tcp(target) else http(target)
        } catch (e: IOException) {
            null to classify(e)
        } catch (_: IllegalArgumentException) {
            null to ProbeErrorClass.OTHER
        }
        val totalMs = (ticker.nanos() - started) / NANOS_IN_MS
        return ProbeResult(target.id, error == null, status, error?.code, totalMs)
    }

    private suspend fun http(target: ProbeTarget): Pair<Int?, ProbeErrorClass?> {
        val client = base.newBuilder()
            .callTimeout(Duration.ofMillis(target.timeoutMs))
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        val result = client.send(Request.Builder().url(target.url).get().build())
        return result.code to classifyHttpStatus(result.code, target.expectedStatus)
    }

    private suspend fun tcp(target: ProbeTarget): Pair<Int?, ProbeErrorClass?> = withContext(Dispatchers.IO) {
        val uri = URI(target.url)
        val host = uri.host ?: throw IllegalArgumentException("tcp target without host")
        val port = uri.port.takeIf { it > 0 } ?: throw IllegalArgumentException("tcp target without port")
        Socket().use { it.connect(InetSocketAddress(host, port), target.timeoutMs.toInt()) }
        null to null
    }

    private fun classify(e: IOException): ProbeErrorClass = when (e) {
        is UnknownHostException -> ProbeErrorClass.DNS_FAIL
        is SocketTimeoutException, is InterruptedIOException -> ProbeErrorClass.TIMEOUT
        is ConnectException -> ProbeErrorClass.CONN_REFUSED
        is SSLException -> ProbeErrorClass.TLS_ERROR
        else -> ProbeErrorClass.OTHER
    }

    private companion object {
        const val TCP = "tcp"
        const val NANOS_IN_MS = 1_000_000L
    }
}
