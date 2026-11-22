package ru.restofleet.server.simulator.domain.synthetic

import ru.restofleet.server.simulator.domain.model.ProbeResult
import kotlin.random.Random

/** Режим проверок доступности: настоящие запросы или результат задаёт сценарий (FR-SM-05). */
enum class ProbeMode(val code: String) {
    REAL("real"),
    SYNTHETIC("synthetic"),
    ;

    companion object {
        fun fromCode(code: String): ProbeMode? = entries.firstOrNull { it.code == code }
    }
}

/** Отказ сети или бэкенда (FR-SM-05) и класс, который должен выдать классификатор (эталон). */
enum class OutageKind(val code: String, val expectedClass: String) {
    /** Сеть ресторана: устройства молча пропадают ([NetworkVariant.OFFLINE]) или недоступно всё, включая `ref`. */
    RESTAURANT_NETWORK_DOWN("restaurant_network_down", "restaurant_network"),

    /** Основная цель недоступна из всех ресторанов при живом `ref`. */
    BACKEND_GLOBAL_DOWN("backend_global_down", "backend_global"),

    /** Основная цель недоступна из перечисленных ресторанов при живом `ref`. */
    BACKEND_LOCAL_DOWN("backend_local_down", "backend_local"),

    /** Одно устройство пропадает молча при живых соседях (R-01, без инцидента). */
    DEVICE_DOWN("device_down", "device"),

    /** Ответы успешны, но медленные (R-10 — диплом, в матрицу точности не входит, реш. 428). */
    DEGRADATION("degradation", "degradation"),
    ;

    companion object {
        fun fromCode(code: String): OutageKind? = entries.firstOrNull { it.code == code }
    }
}

enum class NetworkVariant(val code: String) {
    OFFLINE("offline"),
    REF_DOWN("ref_down"),
    ;

    companion object {
        fun fromCode(code: String): NetworkVariant? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Отказ в окне `[fromMs, toMs)` от старта прогона. [restaurants] — коды ресторанов (для `backend_global_down` пусто —
 * все), [devices] — id устройств (`device_down`).
 */
data class Outage(
    val kind: OutageKind,
    val fromMs: Long,
    val toMs: Long,
    val restaurants: Set<String> = emptySet(),
    val devices: Set<String> = emptySet(),
    val variant: NetworkVariant = NetworkVariant.OFFLINE,
    val latencyMs: Long = DEFAULT_DEGRADED_MS,
) {
    init {
        require(toMs > fromMs) { "outage must end after it starts" }
    }

    fun activeAt(elapsedMs: Long): Boolean = elapsedMs in fromMs until toMs

    private fun inRestaurant(code: String) = restaurants.isEmpty() || code in restaurants

    /** Отказ касается устройства [deviceId] ресторана [code]. */
    fun touches(deviceId: String, code: String): Boolean = when (kind) {
        OutageKind.DEVICE_DOWN -> deviceId in devices
        OutageKind.BACKEND_GLOBAL_DOWN -> true
        else -> inRestaurant(code)
    }

    companion object {
        const val DEFAULT_DEGRADED_MS = 3_000L
    }
}

/** Строка эталонной разметки: ресторан (и устройство у `device`), ожидаемый класс, интервал (epoch ms). */
data class TruthRow(
    val scenario: String,
    val restaurant: String,
    val device: String?,
    val expectedClass: String,
    val from: Long,
    val to: Long,
)

/**
 * Синтетическая сеть прогона (FR-SM-05): по активным отказам отвечает, что видит проба устройства и не пропало ли
 * оно. Чистая функция времени — один и тот же сценарий всегда даёт одни и те же результаты.
 */
class SyntheticNetwork(
    private val outages: List<Outage>,
    private val startMillis: Long,
    private val primaryTarget: String = PRIMARY,
) {
    private fun active(deviceId: String, code: String, now: Long) =
        outages.filter { it.activeAt(now - startMillis) && it.touches(deviceId, code) }

    /** Устройство молча пропало (сеть ресторана в варианте `offline` или `device_down`). */
    fun offline(deviceId: String, code: String, now: Long): Boolean = active(deviceId, code, now).any {
        it.kind == OutageKind.DEVICE_DOWN ||
            (it.kind == OutageKind.RESTAURANT_NETWORK_DOWN && it.variant == NetworkVariant.OFFLINE)
    }

    fun probe(deviceId: String, code: String, target: String, now: Long): ProbeResult {
        val effects = active(deviceId, code, now)
        val networkDown = effects.any {
            it.kind == OutageKind.RESTAURANT_NETWORK_DOWN && it.variant == NetworkVariant.REF_DOWN
        }
        val backendDown = target == primaryTarget && effects.any {
            it.kind == OutageKind.BACKEND_GLOBAL_DOWN || it.kind == OutageKind.BACKEND_LOCAL_DOWN
        }
        val slow = effects.firstOrNull { it.kind == OutageKind.DEGRADATION && target == primaryTarget }
        return when {
            networkDown || backendDown ->
                ProbeResult(target, ok = false, status = null, error = TIMEOUT, totalMs = TIMEOUT_MS)
            slow != null -> ProbeResult(target, ok = true, status = OK, error = null, totalMs = slow.latencyMs)
            else -> ProbeResult(target, ok = true, status = OK, error = null, totalMs = HEALTHY_MS)
        }
    }

    companion object {
        const val PRIMARY = "api"
        const val TIMEOUT = "timeout"
        const val TIMEOUT_MS = 5_000L
        const val HEALTHY_MS = 40L
        const val OK = 204
    }
}

/**
 * Генератор эпизодов точности (реш. 428): раунды по [roundSec]; раунд — либо глобальный отказ бэкенда, либо смесь
 * одновременных независимых эпизодов в разных ресторанах: сеть одного ресторана, бэкенд локально у двух, одно
 * устройство, остальные — без отказа (отрицательные примеры). Эпизод активен с [activeFromSec] по
 * [activeFromSec] + [activeSec] секунду раунда; остаток раунда — на закрытие инцидентов. Детерминирован от [seed].
 */
class EpisodeGenerator(
    private val seed: Long,
    private val roundSec: Int = DEFAULT_ROUND_SEC,
    private val activeFromSec: Int = DEFAULT_ACTIVE_FROM_SEC,
    private val activeSec: Int = DEFAULT_ACTIVE_SEC,
    private val globalShare: Double = DEFAULT_GLOBAL_SHARE,
) {
    init {
        require(activeFromSec >= 0 && activeSec >= 1 && activeFromSec + activeSec < roundSec) {
            "episode must fit into its round"
        }
    }

    data class Plan(val outages: List<Outage>, val truth: List<TruthRow>)

    /**
     * [devices] — устройства по коду ресторана (порядок важен для детерминизма); [startMillis] — старт прогона, время
     * эталона — абсолютное (epoch ms).
     */
    fun generate(scenario: String, rounds: Int, devices: Map<String, List<String>>, startMillis: Long): Plan {
        require(devices.size >= MIN_RESTAURANTS) { "need at least $MIN_RESTAURANTS restaurants for mixed rounds" }
        val random = Random(seed)
        val codes = devices.keys.sorted()
        val outages = mutableListOf<Outage>()
        val truth = mutableListOf<TruthRow>()
        repeat(rounds) { round ->
            val from = (round * roundSec + activeFromSec) * MS
            val to = from + activeSec * MS
            fun row(code: String, cls: String, device: String? = null) =
                TruthRow(scenario, code, device, cls, startMillis + from, startMillis + to)
            if (random.nextDouble() < globalShare) {
                outages += Outage(OutageKind.BACKEND_GLOBAL_DOWN, from, to)
                codes.forEach { truth += row(it, OutageKind.BACKEND_GLOBAL_DOWN.expectedClass) }
            } else {
                val shuffled = codes.shuffled(random)
                val network = shuffled[0]
                val local = shuffled.subList(1, LOCAL_END)
                val single = shuffled[LOCAL_END]
                val variant = if (random.nextBoolean()) NetworkVariant.OFFLINE else NetworkVariant.REF_DOWN
                outages += Outage(OutageKind.RESTAURANT_NETWORK_DOWN, from, to, setOf(network), variant = variant)
                outages += Outage(OutageKind.BACKEND_LOCAL_DOWN, from, to, local.toSet())
                val device = devices.getValue(single).let { it[random.nextInt(it.size)] }
                outages += Outage(OutageKind.DEVICE_DOWN, from, to, devices = setOf(device))
                truth += row(network, OutageKind.RESTAURANT_NETWORK_DOWN.expectedClass)
                local.forEach { truth += row(it, OutageKind.BACKEND_LOCAL_DOWN.expectedClass) }
                truth += row(single, OutageKind.DEVICE_DOWN.expectedClass, device)
                shuffled.drop(LOCAL_END + 1).forEach { truth += row(it, NONE) }
            }
        }
        return Plan(outages, truth)
    }

    companion object {
        const val DEFAULT_ROUND_SEC = 300
        const val DEFAULT_ACTIVE_FROM_SEC = 30
        const val DEFAULT_ACTIVE_SEC = 150
        const val DEFAULT_GLOBAL_SHARE = 0.25
        const val NONE = "none"
        private const val MS = 1_000L

        /** Сеть — 1, локально — 2, устройство — 1, и хотя бы один без отказа. */
        private const val MIN_RESTAURANTS = 5
        private const val LOCAL_END = 3
    }
}

/** Эталон для явно заданных отказов сценария (s8-a/б/в): строка на ресторан (и устройство у `device_down`). */
object ExplicitTruth {
    fun of(
        scenario: String,
        outages: List<Outage>,
        devices: Map<String, List<String>>,
        startMillis: Long,
    ): List<TruthRow> = outages.flatMap { outage ->
        val from = startMillis + outage.fromMs
        val to = startMillis + outage.toMs
        when (outage.kind) {
            OutageKind.BACKEND_GLOBAL_DOWN ->
                devices.keys.sorted().map { TruthRow(scenario, it, null, outage.kind.expectedClass, from, to) }
            OutageKind.DEVICE_DOWN -> outage.devices.sorted().mapNotNull { id ->
                devices.entries.firstOrNull { id in it.value }?.key
                    ?.let { TruthRow(scenario, it, id, outage.kind.expectedClass, from, to) }
            }
            else -> outage.restaurants.sorted().map {
                TruthRow(scenario, it, null, outage.kind.expectedClass, from, to)
            }
        }
    }
}
