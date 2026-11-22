package ru.restofleet.console.core.common

/** Форматирование времени: «8 с назад», «7 мин», «3 ч 12 мин». Чистые функции. */
object RelativeTime {
    private const val SEC = 1_000L
    private const val MIN = 60 * SEC
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR
    private const val TEN = 10L

    /** Длительность: с / мин / «ч мин» / «д ч»; отрицательная — «0 с». */
    fun duration(ms: Long): String {
        val v = if (ms < 0) 0 else ms
        return when {
            v < MIN -> "${v / SEC} с"
            v < HOUR -> "${v / MIN} мин"
            v < DAY -> hoursMinutes(v)
            else -> "${v / DAY} д ${(v % DAY) / HOUR} ч"
        }
    }

    /** «N назад» для события в прошлом; будущее время (рассинхрон часов) считается «сейчас». */
    fun ago(nowMs: Long, tsMs: Long): String {
        val d = nowMs - tsMs
        return if (d < SEC) "сейчас" else "${duration(d)} назад"
    }

    private fun hoursMinutes(v: Long): String {
        val h = v / HOUR
        val m = (v % HOUR) / MIN
        return if (m == 0L) "$h ч" else "$h ч $m мин"
    }

    /** Обратный отсчёт `чч:мм:сс`; отрицательный — `00:00:00`. */
    fun countdown(ms: Long): String {
        val total = (if (ms < 0) 0 else ms) / SEC
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "${h.pad()}:${m.pad()}:${s.pad()}"
    }

    private fun Long.pad(): String = if (this < TEN) "0$this" else "$this"
}
