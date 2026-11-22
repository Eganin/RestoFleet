package ru.restofleet.server.domain.incident

import ru.restofleet.server.domain.restaurant.RestaurantId
import kotlin.math.ceil

/** Пороги и цели классификатора (реш. 409, 410); задаются в конфиге `incidents.*`. */
data class ClassifierParams(
    /** P: % онлайн-устройств ресторана со свежими данными по [primaryTarget], видящих её недоступной. */
    val backendDownPct: Int = 50,
    /** Нижняя граница M. */
    val minRestaurants: Int = 3,
    /** Доля ресторанов N в M, %: M = max([minRestaurants], ⌈доля·N⌉). */
    val restaurantSharePct: Int = 25,
    /** «Сеть ресторана»: ≥ этого % учитываемых устройств офлайн (при ≥ 2 устройствах). */
    val offlinePct: Int = 50,
    val primaryTarget: String = "api",
    val refTarget: String = "ref",
) {
    init {
        require(backendDownPct in 1..PERCENT && offlinePct in 1..PERCENT && restaurantSharePct in 0..PERCENT)
        require(minRestaurants >= 1)
    }

    /** M для [n] ресторанов с данными. */
    fun m(n: Int): Int = maxOf(minRestaurants, ceil(n * restaurantSharePct / PERCENT.toDouble()).toInt())
}

/** 100 % — множитель сравнения долей без деления. */
internal const val PERCENT = 100

/** Желаемое состояние инцидента на тике. */
data class Classified(val kind: IncidentKind, val target: String, val restaurants: List<RestaurantId>)

/** Итог классификатора: инциденты и пороги тика. */
data class Classification(val incidents: List<Classified>, val thresholds: IncidentThresholds)

/**
 * Классификатор причины недоступности (FR-BE-36, спецификация 4.2, реш. 409, 410) — чистая функция:
 * 1. ≥ [ClassifierParams.offlinePct] % устройств ресторана офлайн (при ≥ 2 учитываемых) **или** `ref` недоступен со
 *    всех онлайн-устройств с данными по нему (нужно ≥ 1 такое) → «сеть ресторана» (R-07);
 * 2. иначе A — рестораны, где [ClassifierParams.primaryTarget] недоступен у ≥ P % онлайн-устройств с данными по нему
 *    при живом `ref` (≥ 1 устройство видит его доступным): |A| ≥ M → один «бэкенд глобально» (R-08), 0 < |A| < M —
 *    «бэкенд локально» (R-09) на каждый ресторан;
 * 3. одиночное офлайн-устройство — R-01 без инцидента.
 * N — рестораны со свежими данными по основной цели.
 */
object IncidentClassifier {
    fun classify(stats: List<RestaurantStats>, params: ClassifierParams = ClassifierParams()): Classification {
        val network = stats.filter { isNetworkDown(it, params) }
        val networkIds = network.map { it.restaurantId }.toSet()
        val withData = stats.filter { it.target(params.primaryTarget).online > 0 }
        val n = withData.size
        val m = params.m(n)
        val backendDown = withData.filter { it.restaurantId !in networkIds && isBackendDown(it, params) }
            .map { it.restaurantId }
        val networkIncidents = network.map {
            Classified(IncidentKind.RESTAURANT_NETWORK, params.refTarget, listOf(it.restaurantId))
        }
        val backendIncidents = when {
            backendDown.size >= m -> listOf(Classified(IncidentKind.BACKEND_GLOBAL, params.primaryTarget, backendDown))
            else -> backendDown.map { Classified(IncidentKind.BACKEND_LOCAL, params.primaryTarget, listOf(it)) }
        }
        val incidents = networkIncidents + backendIncidents
        return Classification(incidents, IncidentThresholds(params.backendDownPct, m, n))
    }

    private fun isNetworkDown(s: RestaurantStats, params: ClassifierParams): Boolean {
        val offline = s.devicesTotal - s.devicesOnline
        val mostlyOffline = s.devicesTotal >= MIN_DEVICES_FOR_SHARE &&
            offline * PERCENT >= params.offlinePct * s.devicesTotal
        val ref = s.target(params.refTarget)
        return mostlyOffline || (ref.online >= 1 && ref.up == 0)
    }

    private fun isBackendDown(s: RestaurantStats, params: ClassifierParams): Boolean {
        val primary = s.target(params.primaryTarget)
        val refAlive = s.target(params.refTarget).up >= 1
        return primary.online >= 1 && primary.down * PERCENT >= params.backendDownPct * primary.online && refAlive
    }

    /** Доля офлайн имеет смысл при ≥ 2 устройствах: одно устройство — R-01, не R-07 (реш. 410). */
    private const val MIN_DEVICES_FOR_SHARE = 2
}
