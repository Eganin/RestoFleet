package ru.restofleet.agent.data.probes

import android.net.IpPrefix
import android.net.LinkProperties
import android.net.RouteInfo
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.collectors.appContext
import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.shared.kernel.Clock
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import android.net.ConnectivityManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class GatewayProbeTest {
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val gateway = InetAddress.getByName("192.168.1.1")

    /** Таблица маршрутов активной сети; конструктор RouteInfo и addRoute скрыты из публичного SDK — через рефлексию. */
    private fun setGatewayRoute(address: InetAddress?) {
        val lp = LinkProperties()
        if (address != null) {
            val prefix = IpPrefix(InetAddress.getByName("0.0.0.0"), 0)
            val route = RouteInfo::class.java.getConstructor(IpPrefix::class.java, InetAddress::class.java)
                .newInstance(prefix, address)
            LinkProperties::class.java.getMethod("addRoute", RouteInfo::class.java).invoke(lp, route)
        }
        shadowOf(cm).setLinkProperties(cm.activeNetwork, lp)
    }

    private class RecordingSocket(private val onConnect: (SocketAddress, Int) -> Unit) : Socket() {
        var connectedTo: SocketAddress? = null
        var timeout: Int? = null
        val closedFlag = AtomicBoolean(false)

        override fun connect(endpoint: SocketAddress, timeout: Int) {
            connectedTo = endpoint
            this.timeout = timeout
            onConnect(endpoint, timeout)
        }

        override fun close() {
            closedFlag.set(true)
        }
    }

    private fun probe(
        socket: RecordingSocket,
        port: Int = 80,
        timeoutMs: Int = 3_000,
        nanos: () -> Long = System::nanoTime,
    ) = runBlocking { GatewayProbe(appContext, { socket }, Clock { TS }, nanos).probe(port, timeoutMs) }

    @Test
    fun connectsToDefaultGatewayAndClosesSocket() {
        setGatewayRoute(gateway)
        val socket = RecordingSocket { _, _ -> }
        val ticks = generateSequence(0L) { it + 7_000_000L }.iterator()
        val r = probe(socket, port = 8080, timeoutMs = 1_234, nanos = { ticks.next() })
        assertThat(socket.connectedTo).isEqualTo(InetSocketAddress(gateway, 8080))
        assertThat(socket.timeout).isEqualTo(1_234)
        assertThat(socket.closedFlag.get()).isTrue()
        assertThat(r.targetId).isEqualTo("gateway")
        assertThat(r.ok).isTrue()
        assertThat(r.httpStatus as Any?).isNull()
        assertThat(r.error as Any?).isNull()
        assertThat(r.totalMs).isEqualTo(7L)
        assertThat(r.phases.connectMs).isEqualTo(7L)
        assertThat(r.phases.dnsMs as Any?).isNull()
        assertThat(r.phases.tlsMs as Any?).isNull()
        assertThat(r.phases.ttfbMs as Any?).isNull()
        assertThat(r.ts).isEqualTo(TS)
    }

    @Test
    fun epermIsLocalNetworkDenied() {
        setGatewayRoute(gateway)
        val socket = RecordingSocket { _, _ -> throw SocketException("EPERM (Operation not permitted)") }
        val r = probe(socket)
        assertThat(r.ok).isFalse()
        assertThat(r.error).isEqualTo(ProbeError.LOCAL_NETWORK_DENIED)
        assertThat(r.phases.connectMs as Any?).isNull()
        assertThat(socket.closedFlag.get()).isTrue()
    }

    @Test
    fun refusedAndTimeoutAreClassified() {
        setGatewayRoute(gateway)
        val refused = probe(RecordingSocket { _, _ -> throw ConnectException("Connection refused") })
        assertThat(refused.error).isEqualTo(ProbeError.CONN_REFUSED)
        val timeout = probe(RecordingSocket { _, _ -> throw SocketTimeoutException("connect timed out") })
        assertThat(timeout.error).isEqualTo(ProbeError.TIMEOUT)
    }

    @Test
    fun noGatewayRouteIsNoNetwork() {
        setGatewayRoute(null)
        var factoryCalled = false
        val r = runBlocking {
            GatewayProbe(appContext, { factoryCalled = true; Socket() }, Clock { TS }).probe()
        }
        assertThat(r.ok).isFalse()
        assertThat(r.error).isEqualTo(ProbeError.NO_NETWORK)
        assertThat(factoryCalled).isFalse()
    }

    @Test
    fun noActiveNetworkIsNoNetwork() {
        shadowOf(cm).setActiveNetworkInfo(null)
        val r = probe(RecordingSocket { _, _ -> })
        assertThat(r.error).isEqualTo(ProbeError.NO_NETWORK)
    }

    @Test
    fun cancellationClosesTheBlockedSocket() = runBlocking {
        setGatewayRoute(gateway)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val socket = object : Socket() {
            val closedFlag = AtomicBoolean(false)
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                throw SocketException("closed")
            }

            override fun close() {
                closedFlag.set(true)
                release.countDown()
            }
        }
        val job = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            GatewayProbe(appContext, { socket }, Clock { TS }).probe()
        }
        withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) }
        val cancelledAt = System.nanoTime()
        job.cancel()
        runCatching { job.await() }
        // Без закрытия сокета connect висел бы до release (10 с): отмена должна завершаться почти сразу.
        assertThat(System.nanoTime() - cancelledAt).isLessThan(3_000_000_000L)
        assertThat(socket.closedFlag.get()).isTrue()
    }

    private companion object {
        const val TS = 42L
    }
}
