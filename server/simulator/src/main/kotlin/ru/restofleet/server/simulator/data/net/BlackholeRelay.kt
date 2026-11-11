package ru.restofleet.server.simulator.data.net

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Локальное TCP-реле устройства, которое в замере «пропадёт» (план 4.2: оборвать устройство без DISCONNECT). Клиент
 * подключается к `127.0.0.1:[port]`, реле прозрачно гонит байты (TLS сквозной) к [upstreamHost]:[upstreamPort].
 * [freeze] — связь замолкает: байты больше не передаются ни в одну сторону, соединение с брокером остаётся открытым
 * (ни DISCONNECT, ни FIN — брокер узнаёт о пропаже только по keep-alive, как при пропаже сети). [cut] — соединение
 * с брокером закрывается (FIN без DISCONNECT — как при убитом процессе). Потоки — демоны, по два на соединение.
 */
class BlackholeRelay(private val upstreamHost: String, private val upstreamPort: Int) : Closeable {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val upstreams = CopyOnWriteArrayList<Socket>()

    @Volatile private var frozen = false

    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "relay-accept-$port") { acceptLoop() }
    }

    fun freeze() {
        frozen = true
    }

    fun cut() {
        frozen = true
        upstreams.forEach { closeQuietly(it) }
    }

    override fun close() {
        frozen = true
        closeQuietly(server)
        sockets.forEach { closeQuietly(it) }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            sockets += client
            // После «пропажи» новые подключения не доходят до брокера: держим их открытыми и молчим
            if (!frozen) bridge(client)
        }
    }

    private fun bridge(client: Socket) {
        val upstream = try {
            Socket(upstreamHost, upstreamPort)
        } catch (_: IOException) {
            closeQuietly(client)
            return
        }
        sockets += upstream
        upstreams += upstream
        pump(client, upstream)
        pump(upstream, client)
    }

    private fun pump(from: Socket, to: Socket) = thread(isDaemon = true, name = "relay-pump-$port") {
        val buffer = ByteArray(BUFFER)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (!frozen) {
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }
        } catch (_: IOException) {
            // соединение закрыто одной из сторон
        }
        // Закрытие передаётся дальше только до «пропажи»: иначе брокер получил бы FIN
        if (!frozen) closeQuietly(to)
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
            // уже закрыто
        }
    }

    private companion object {
        const val BACKLOG = 16
        const val BUFFER = 16 * 1024
    }
}
