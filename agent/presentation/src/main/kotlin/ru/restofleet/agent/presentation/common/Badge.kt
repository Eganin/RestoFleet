package ru.restofleet.agent.presentation.common

import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus

/** Значок статуса строки проверки (Lab и экран Device Owner). */
enum class Badge { OK, WARN, FAIL, NA, PENDING }

/** Статус проверки -> значок: LIMITED — предупреждение, UNAVAILABLE — отказ, остальное — «нет данных». */
fun CheckStatus.toBadge(): Badge = when (this) {
    CheckStatus.OK -> Badge.OK
    CheckStatus.LIMITED -> Badge.WARN
    CheckStatus.UNAVAILABLE -> Badge.FAIL
    CheckStatus.NOT_APPLICABLE, CheckStatus.NOT_CHECKED -> Badge.NA
}

/** Текст строки: пояснение, а при его отсутствии — сырые значения `ключ значение · ...`. */
fun CheckResult.summaryText(): String =
    note ?: raw.entries.joinToString(" · ") { (key, value) -> "$key $value" }
