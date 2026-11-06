package ru.restofleet.agent.data.probes

import java.net.InetAddress

/** Маршрут в виде, не зависящем от `RouteInfo`: признак маршрута по умолчанию и адрес шлюза. */
internal data class RouteCandidate(val isDefault: Boolean, val gateway: InetAddress?)

/** Выбор шлюза из таблицы маршрутов (чистая функция). */
internal object GatewayResolver {
    /** Шлюз маршрута по умолчанию; IPv4 предпочтительнее. Адрес `0.0.0.0`/`::` (on-link) не считается шлюзом. */
    fun pick(routes: List<RouteCandidate>): InetAddress? {
        val gateways = routes
            .filter { it.isDefault }
            .mapNotNull { it.gateway }
            .filterNot { it.isAnyLocalAddress }
        return gateways.firstOrNull { it.address.size == IPV4_SIZE } ?: gateways.firstOrNull()
    }

    private const val IPV4_SIZE = 4
}
