package ru.restofleet.console.core.common

/**
 * Тексты правил и тревог на русском: сервер текст не присылает (реш. 434, 436), строка строится из `ruleId`,
 * цели и `details`. Ключи `details`: R-01 `reason`; R-02 `batteryLevel`, `limit`; R-03 `tempC`, `thermal`, `limit`;
 * R-04 `pkg`, `sinceTs`, `limitMin`; R-05 `error`, `failures`; R-06 `freeMb`, `limit`.
 * Неизвестное правило — код как есть.
 */
object RuleTexts {
    fun title(ruleId: String, target: String? = null): String = when (ruleId) {
        "R-01" -> "Устройство офлайн"
        "R-02" -> "Низкий заряд"
        "R-03" -> "Перегрев"
        "R-04" -> "Рабочее приложение не на экране"
        "R-05" -> if (target.isNullOrBlank()) "Цель недоступна" else "Цель $target недоступна"
        "R-06" -> "Мало свободной памяти"
        "R-07" -> "Сеть ресторана"
        "R-08" -> "Бэкенд недоступен (все рестораны)"
        "R-09" -> "Бэкенд недоступен (ресторан)"
        else -> ruleId
    }

    /** Краткая подробность строки тревоги: «46.8 °C · SEVERE», «заряд 18 %», «timeout ×3». Пусто, если данных нет. */
    fun detail(ruleId: String, details: Map<String, String>, nowMs: Long): String = when (ruleId) {
        "R-01" -> reasonText(details["reason"])
        "R-02" -> details["batteryLevel"]?.let { "заряд $it %" }.orEmpty()
        "R-03" -> listOfNotNull(
            details["tempC"]?.let { "$it °C" },
            details["thermal"],
        ).joinToString(" · ")
        "R-04" -> r04(details, nowMs)
        "R-05" -> listOfNotNull(details["error"], details["failures"]?.let { "×$it" }).joinToString(" ")
        "R-06" -> details["freeMb"]?.let { "свободно $it МБ" }.orEmpty()
        else -> ""
    }

    /** Полная строка: «Перегрев · 46.8 °C · SEVERE». */
    fun line(ruleId: String, target: String?, details: Map<String, String>, nowMs: Long): String =
        listOf(title(ruleId, target), detail(ruleId, details, nowMs)).filter { it.isNotBlank() }.joinToString(" · ")

    private fun reasonText(reason: String?): String = when (reason) {
        null, "" -> ""
        "lwt" -> "обрыв связи (LWT)"
        "keepalive" -> "нет keepalive"
        "disconnect" -> "отключился"
        else -> reason
    }

    private fun r04(details: Map<String, String>, nowMs: Long): String {
        val since = details["sinceTs"]?.toLongOrNull()
        val minutes = since?.let { ((nowMs - it) / MS_IN_MIN).coerceAtLeast(0) }
        val pkg = details["pkg"]
        return listOfNotNull(pkg, minutes?.let { "$it мин" }).joinToString(" · ")
    }

    private const val MS_IN_MIN = 60_000L
}
