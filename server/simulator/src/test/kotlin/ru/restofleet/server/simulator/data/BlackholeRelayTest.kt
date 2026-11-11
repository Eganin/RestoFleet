package ru.restofleet.server.simulator.data

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import ru.restofleet.server.simulator.data.net.BlackholeRelay
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith

/** Реле «пропажи» (план 4.2): прозрачно до [BlackholeRelay.freeze], после — молчит и не закрывает связь с брокером. */
class BlackholeRelayTest {
    /** «Брокер»: эхо-сервер, который сообщает о закрытии соединения клиентом. */
    private val upstream = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
    private val upstreamClosed = LinkedBlockingQueue<Boolean>()

    init {
        thread(isDaemon = true) {
            while (!upstream.isClosed) {
                val socket = runCatching { upstream.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use { s ->
                        val input = s.getInputStream()
                        val output = s.getOutputStream()
                        while (true) {
                            val b = runCatching { input.read() }.getOrDefault(-1)
                            if (b < 0) break
                            output.write(b)
                            output.flush()
                        }
                    }
                    upstreamClosed.put(true)
                }
            }
        }
    }

    private val relay = BlackholeRelay(InetAddress.getLoopbackAddress().hostAddress, upstream.localPort)

    @After
    fun tearDown() {
        relay.close()
        upstream.close()
    }

    private fun client() = Socket(InetAddress.getLoopbackAddress(), relay.port).apply { soTimeout = 500 }

    @Test
    fun forwardsBothWaysUntilFrozenAndThenKeepsUpstreamOpen() {
        val socket = client()
        socket.getOutputStream().write(42)
        assertThat(socket.getInputStream().read()).isEqualTo(42)

        relay.freeze()
        socket.getOutputStream().write(43)
        assertFailsWith<SocketTimeoutException> { socket.getInputStream().read() }
        socket.close()
        // Клиент закрыл своё соединение, но брокер этого не видит: ни FIN, ни DISCONNECT
        assertThat(upstreamClosed.poll(500, TimeUnit.MILLISECONDS)).isNull()

        // Новые подключения после «пропажи» тоже не доходят до брокера
        val late = client()
        late.getOutputStream().write(44)
        assertFailsWith<SocketTimeoutException> { late.getInputStream().read() }
        late.close()
    }

    @Test
    fun cutClosesTheUpstreamConnection() {
        val socket = client()
        socket.getOutputStream().write(1)
        assertThat(socket.getInputStream().read()).isEqualTo(1)
        relay.cut()
        assertThat(upstreamClosed.poll(2, TimeUnit.SECONDS)).isTrue()
        socket.close()
    }

    @Test
    fun closePropagatesNormallyBeforeFreeze() {
        val socket = client()
        socket.getOutputStream().write(7)
        assertThat(socket.getInputStream().read()).isEqualTo(7)
        socket.close()
        assertThat(upstreamClosed.poll(2, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun unreachableUpstreamClosesTheClient() {
        val dead = ServerSocket(0).also { it.close() }.localPort
        BlackholeRelay("127.0.0.1", dead).use { broken ->
            val socket = Socket(InetAddress.getLoopbackAddress(), broken.port).apply { soTimeout = 2_000 }
            assertThat(socket.getInputStream().read()).isEqualTo(-1)
            socket.close()
        }
    }
}
