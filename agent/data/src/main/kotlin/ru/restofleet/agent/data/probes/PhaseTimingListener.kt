package ru.restofleet.agent.data.probes

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import ru.restofleet.agent.domain.model.ProbePhases
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Замеряет фазы одного вызова OkHttp (по экземпляру на вызов). Время — монотонное, [nanoTime] инжектируется.
 * Фаза без завершения остаётся null. Повторные попытки соединения: учитывается последняя.
 */
internal class PhaseTimingListener(private val nanoTime: () -> Long = System::nanoTime) : EventListener() {
    private var callStart: Long? = null
    private var dnsStart: Long? = null
    private var dnsEnd: Long? = null
    private var connectStart: Long? = null
    private var connectEnd: Long? = null
    private var tlsStart: Long? = null
    private var tlsEnd: Long? = null
    private var headersStart: Long? = null

    /** Снимок на текущий момент. `connectMs` — только TCP (без TLS, который OkHttp вкладывает в connect). */
    fun phases(): ProbePhases {
        val tls = span(tlsStart, tlsEnd)
        return ProbePhases(
            dnsMs = span(dnsStart, dnsEnd),
            connectMs = span(connectStart, connectEnd)?.let { it - (tls ?: 0L) },
            tlsMs = tls,
            ttfbMs = span(callStart, headersStart),
        )
    }

    private fun span(start: Long?, end: Long?): Long? =
        if (start != null && end != null) (end - start) / NANOS_IN_MS else null

    override fun callStart(call: Call) {
        callStart = nanoTime()
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStart = nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsEnd = nanoTime()
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStart = nanoTime()
        connectEnd = null
        tlsStart = null
        tlsEnd = null
    }

    override fun secureConnectStart(call: Call) {
        tlsStart = nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsEnd = nanoTime()
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        connectEnd = nanoTime()
    }

    override fun responseHeadersStart(call: Call) {
        headersStart = nanoTime()
    }

    private companion object {
        const val NANOS_IN_MS = 1_000_000L
    }
}
