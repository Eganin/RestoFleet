package ru.restofleet.server.domain.incident

import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId

/** Учитываемое устройство (реш. 410): не выведено, виделось за последние 24 ч. */
data class FleetDevice(val deviceId: DeviceId, val restaurantId: RestaurantId, val online: Boolean)

/** Свежее состояние «устройство × цель» (автомат K агента, реш. 411); [error] — класс последней ошибки. */
data class ProbeObservation(val deviceId: DeviceId, val target: String, val down: Boolean, val error: String?)

/** Цель в ресторане: [online] — онлайн-устройства со свежими данными, [down] — из них видят цель недоступной. */
data class TargetStats(val online: Int, val down: Int, val errorClasses: Map<String, Int>) {
    val up: Int get() = online - down
}

/** Ресторан в окне агрегатора. */
data class RestaurantStats(
    val restaurantId: RestaurantId,
    val devicesTotal: Int,
    val devicesOnline: Int,
    val targets: Map<String, TargetStats>,
) {
    fun target(name: String): TargetStats = targets[name] ?: EMPTY

    private companion object {
        val EMPTY = TargetStats(0, 0, emptyMap())
    }
}

/**
 * Агрегатор проверок (FR-BE-35, реш. 411) — чистая функция: по ресторану — учитываемые и онлайн-устройства и по каждой
 * цели — сколько онлайн-устройств со свежими данными её видят и сколько из них — недоступной. Наблюдения офлайн и
 * неучитываемых устройств отбрасываются (реш. 410).
 */
object ProbeAggregator {
    fun aggregate(fleet: List<FleetDevice>, observations: List<ProbeObservation>): List<RestaurantStats> {
        val online = fleet.filter { it.online }.associateBy { it.deviceId }
        val byRestaurant = observations.mapNotNull { o -> online[o.deviceId]?.let { it.restaurantId to o } }
            .groupBy({ it.first }, { it.second })
        return fleet.groupBy { it.restaurantId }.map { (restaurantId, devices) ->
            val targets = byRestaurant[restaurantId].orEmpty()
                .distinctBy { it.deviceId to it.target }
                .groupBy { it.target }
                .mapValues { (_, obs) ->
                    val down = obs.filter { it.down }
                    TargetStats(obs.size, down.size, down.mapNotNull { it.error }.groupingBy { it }.eachCount())
                }
            RestaurantStats(restaurantId, devices.size, devices.count { it.online }, targets)
        }.sortedBy { it.restaurantId.value }
    }
}
