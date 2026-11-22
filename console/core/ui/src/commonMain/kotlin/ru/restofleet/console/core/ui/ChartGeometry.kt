package ru.restofleet.console.core.ui

/** Точка данных графика: время в мс и значение. */
data class ChartPoint(val x: Long, val y: Double)

/** Точка в пикселях холста. */
data class PxPoint(val x: Float, val y: Float)

/** Результат масштабирования: линия, положение порога и подписи оси Y. */
data class ChartGeometry(
    val line: List<PxPoint>,
    val thresholdY: Float?,
    val minY: Double,
    val maxY: Double,
)

/**
 * Масштабирование данных графика под размер холста (реш. 421) — чистая функция, проверяется тестами.
 * Диапазон Y включает порог, чтобы линия порога всегда была видна; плоский ряд получает запас ±1.
 */
object ChartScale {
    private const val PAD_RATIO = 0.1
    private const val FLAT_PAD = 1.0

    fun build(points: List<ChartPoint>, threshold: Double?, width: Float, height: Float): ChartGeometry {
        val ys = points.map { it.y } + listOfNotNull(threshold)
        val lo = ys.minOrNull() ?: 0.0
        val hi = ys.maxOrNull() ?: 0.0
        val pad = if (hi == lo) FLAT_PAD else (hi - lo) * PAD_RATIO
        val minY = lo - pad
        val maxY = hi + pad
        val minX = points.minOfOrNull { it.x } ?: 0L
        val spanX = ((points.maxOfOrNull { it.x } ?: 0L) - minX).coerceAtLeast(1L)

        fun px(x: Long): Float = if (points.size == 1) width / 2f else (x - minX).toFloat() / spanX * width
        fun py(y: Double): Float = (height * (1 - (y - minY) / (maxY - minY))).toFloat()

        return ChartGeometry(
            line = points.sortedBy { it.x }.map { PxPoint(px(it.x), py(it.y)) },
            thresholdY = threshold?.let { py(it) },
            minY = minY,
            maxY = maxY,
        )
    }
}

/** Фаза проверки (DNS, TCP, TLS, TTFB) и её доля в полосе водопада. */
data class PhaseSegment(val name: String, val ms: Long)

data class PhaseBar(val name: String, val ms: Long, val offset: Float, val width: Float)

/** Раскладка водопада: фазы идут друг за другом, ширина пропорциональна времени, нулевые фазы пропускаются. */
object PhaseLayout {
    fun build(phases: List<PhaseSegment>, width: Float): List<PhaseBar> {
        val visible = phases.filter { it.ms > 0 }
        val total = visible.sumOf { it.ms }.coerceAtLeast(1L)
        var offset = 0f
        return visible.map {
            val w = it.ms.toFloat() / total * width
            PhaseBar(it.name, it.ms, offset, w).also { offset += w }
        }
    }
}
