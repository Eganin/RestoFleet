package ru.restofleet.server.simulator.data.net

import ru.restofleet.server.simulator.domain.model.TrafficSnapshot
import ru.restofleet.server.simulator.domain.port.TrafficMeter
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Трафик устройств по счётчикам ядра Linux `/sys/class/net/<iface>/statistics/{rx,tx}_bytes` (план 4.2: «по счётчику
 * байт сокета или NET I/O контейнера»): считаются IP-пакеты целиком — заголовки TCP/IP, TLS, keep-alive, ACK.
 * Интерфейс — тот, через который идёт трафик к адресу устройств (брокер или API); измеритель ходит к бэкенду через
 * другой интерфейс (сеть `control`, реш. 280) и в счётчик не попадает. Вне Linux-контейнера ([iface] `null`) — `null`.
 */
class InterfaceTrafficMeter(private val iface: String?, private val sysNet: Path = Path.of(SYS_NET)) : TrafficMeter {
    override fun snapshot(): TrafficSnapshot? {
        val name = iface ?: return null
        return try {
            TrafficSnapshot(read(name, "rx_bytes"), read(name, "tx_bytes"))
        } catch (_: IOException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun read(name: String, counter: String): Long =
        Files.readString(sysNet.resolve(name).resolve("statistics").resolve(counter)).trim().toLong()

    companion object {
        const val SYS_NET = "/sys/class/net"

        /** Имя интерфейса, в подсети которого лежит адрес [host]; не нашли или имя не резолвится — `null`. */
        fun interfaceFor(host: String): String? {
            val target = try {
                InetAddress.getByName(host)
            } catch (_: UnknownHostException) {
                null
            }
            if (target !is Inet4Address || target.isLoopbackAddress) return null
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            return interfaces.firstOrNull { network ->
                network.interfaceAddresses.any { address ->
                    val local = address.address
                    local is Inet4Address && sameSubnet(local, target, address.networkPrefixLength.toInt())
                }
            }?.name
        }

        fun sameSubnet(a: Inet4Address, b: Inet4Address, prefix: Int): Boolean {
            if (prefix !in 1..IPV4_BITS) return false
            val mask = (-1 shl (IPV4_BITS - prefix))
            return (a.toInt() and mask) == (b.toInt() and mask)
        }

        private fun Inet4Address.toInt(): Int =
            address.fold(0) { acc, byte -> (acc shl BYTE_BITS) or (byte.toInt() and BYTE_MASK) }

        private const val IPV4_BITS = 32
        private const val BYTE_BITS = 8
        private const val BYTE_MASK = 0xFF
    }
}
