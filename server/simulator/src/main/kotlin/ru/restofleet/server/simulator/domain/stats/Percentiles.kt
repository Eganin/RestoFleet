package ru.restofleet.server.simulator.domain.stats

import kotlin.math.ceil

/** Сводка выборки задержек, мс. */
data class LatencySummary(val count: Int, val p50: Double, val p95: Double, val max: Double, val mean: Double)

/** Перцентили по методу ближайшего ранга (nearest-rank): значение — всегда элемент выборки. */
object Percentiles {
    fun of(values: List<Double>, percent: Double): Double {
        require(values.isNotEmpty()) { "empty sample" }
        require(percent > 0.0 && percent <= HUNDRED) { "percent must be in (0, 100]" }
        val sorted = values.sorted()
        val rank = ceil(percent / HUNDRED * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun summary(values: List<Double>): LatencySummary? = values.takeIf { it.isNotEmpty() }?.let {
        LatencySummary(it.size, of(it, P50), of(it, P95), it.max(), it.average())
    }

    private const val HUNDRED = 100.0
    private const val P50 = 50.0
    private const val P95 = 95.0
}
