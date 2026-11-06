package ru.restofleet.agent.data.probes

import com.google.common.truth.Truth.assertThat
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import ru.restofleet.agent.domain.model.ProbePhases
import java.net.InetSocketAddress
import java.net.Proxy

class PhaseTimingListenerTest {
    private var nowMs = 1_000L
    private val listener = PhaseTimingListener { nowMs * 1_000_000L }
    private val call: Call = OkHttpClient().newCall(Request.Builder().url("https://h/").build())

    private fun at(ms: Long, event: () -> Unit) {
        nowMs = ms
        event()
    }

    private val addr = InetSocketAddress.createUnresolved("h", 443)

    @Test
    fun httpsCallYieldsAllPhases() {
        at(1_000) { listener.callStart(call) }
        at(1_010) { listener.dnsStart(call, "h") }
        at(1_030) { listener.dnsEnd(call, "h", emptyList()) }
        at(1_030) { listener.connectStart(call, addr, Proxy.NO_PROXY) }
        at(1_040) { listener.secureConnectStart(call) }
        at(1_100) { listener.secureConnectEnd(call, null) }
        at(1_100) { listener.connectEnd(call, addr, Proxy.NO_PROXY, null) }
        at(1_150) { listener.responseHeadersStart(call) }
        // connectMs — только TCP: (1100 - 1030) - tls(60) = 10
        assertThat(listener.phases()).isEqualTo(ProbePhases(dnsMs = 20, connectMs = 10, tlsMs = 60, ttfbMs = 150))
    }

    @Test
    fun plainHttpHasNoTls() {
        at(1_000) { listener.callStart(call) }
        at(1_000) { listener.dnsStart(call, "h") }
        at(1_005) { listener.dnsEnd(call, "h", emptyList()) }
        at(1_005) { listener.connectStart(call, addr, Proxy.NO_PROXY) }
        at(1_012) { listener.connectEnd(call, addr, Proxy.NO_PROXY, null) }
        at(1_040) { listener.responseHeadersStart(call) }
        assertThat(listener.phases()).isEqualTo(ProbePhases(dnsMs = 5, connectMs = 7, tlsMs = null, ttfbMs = 40))
    }

    @Test
    fun unfinishedPhasesStayNull() {
        at(1_000) { listener.callStart(call) }
        at(1_000) { listener.dnsStart(call, "h") }
        assertThat(listener.phases()).isEqualTo(ProbePhases(null, null, null, null))
    }

    @Test
    fun failedTlsHandshakeKeepsTcpConnectTime() {
        at(1_000) { listener.callStart(call) }
        at(1_000) { listener.connectStart(call, addr, Proxy.NO_PROXY) }
        at(1_025) { listener.secureConnectStart(call) }
        assertThat(listener.phases().connectMs).isEqualTo(25L)
        assertThat(listener.phases().tlsMs as Any?).isNull()
    }

    @Test
    fun retryOfConnectUsesLastAttempt() {
        at(1_000) { listener.callStart(call) }
        at(1_000) { listener.connectStart(call, addr, Proxy.NO_PROXY) }
        at(1_050) { listener.connectFailed(call, addr, Proxy.NO_PROXY, null, java.io.IOException("x")) }
        at(1_050) { listener.connectStart(call, addr, Proxy.NO_PROXY) }
        at(1_060) { listener.connectEnd(call, addr, Proxy.NO_PROXY, null) }
        assertThat(listener.phases().connectMs).isEqualTo(10L)
    }

    @Test
    fun ttfbWithoutCallStartIsNull() {
        at(1_100) { listener.responseHeadersStart(call) }
        assertThat(listener.phases().ttfbMs as Any?).isNull()
    }
}
