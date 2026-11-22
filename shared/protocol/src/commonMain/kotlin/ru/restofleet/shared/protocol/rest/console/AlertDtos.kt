package ru.restofleet.shared.protocol.rest.console

import kotlinx.serialization.Serializable

/**
 * Тревога (FR-BE-30, 31; реш. 413–416). Субъект — `(ruleId, deviceId, target)`: незакрытая тревога на субъект одна.
 * - [ruleId] — `R-01…R-06` ([RuleIds.ALERT_RULES]); [severity] — [Severities]; [state] — [AlertStates];
 * - [target] — цель проверки у R-05 (`api`, `kds`, `ref`), у остальных правил нет;
 * - [ackBy], [resolvedBy] — `sub` JWT пользователя; [auto] — закрыта автоматически по выходу из условия;
 * - [comment] — комментарий подтверждения или ручного закрытия (1…500 символов);
 * - [parentIncidentId] — тревогу покрывает инцидент (реш. 416): по умолчанию `GET /alerts` её не отдаёт;
 * - [details] — значения условия строками; ключи по правилам: R-01 `reason`; R-02 `batteryLevel`, `limit`;
 *   R-03 `tempC`, `thermal`, `limit`; R-04 `pkg`, `foregroundApp`, `sinceTs`, `limitMin`; R-05 `error`, `failures`;
 *   R-06 `freeMb`, `limit`. Новые ключи могут добавляться; текст для экрана строит консоль.
 */
@Serializable
data class AlertDto(
    val id: String,
    val ruleId: String,
    val severity: String,
    val state: String,
    val deviceId: String,
    val deviceName: String? = null,
    val restaurantId: String,
    val restaurantCode: String,
    val restaurantName: String,
    val target: String? = null,
    val openedAt: Long,
    val ackBy: String? = null,
    val ackAt: Long? = null,
    val resolvedAt: Long? = null,
    val resolvedBy: String? = null,
    val comment: String? = null,
    val auto: Boolean = false,
    val parentIncidentId: String? = null,
    val details: Map<String, String> = emptyMap(),
)

/**
 * `PATCH /alerts/{id}`: [action] — [AlertActions]. `resolve` требует [comment] 1…500 символов (иначе 400
 * `validation`), у `acknowledge` комментарий необязателен. Повтор над закрытой или уже подтверждённой — 409.
 */
@Serializable
data class AlertActionDto(val action: String, val comment: String? = null)

/** Правила ТЗ 6.3: тревоги устройств — R-01…R-06, сводные инциденты — R-07…R-09. */
object RuleIds {
    const val R01 = "R-01"
    const val R02 = "R-02"
    const val R03 = "R-03"
    const val R04 = "R-04"
    const val R05 = "R-05"
    const val R06 = "R-06"
    const val R07 = "R-07"
    const val R08 = "R-08"
    const val R09 = "R-09"
    val ALERT_RULES: List<String> = listOf(R01, R02, R03, R04, R05, R06)
    val ALL: List<String> = ALERT_RULES + listOf(R07, R08, R09)
}

object Severities {
    const val CRITICAL = "critical"
    const val WARNING = "warning"
    val ALL: List<String> = listOf(CRITICAL, WARNING)
}

object AlertStates {
    const val OPEN = "open"
    const val ACKNOWLEDGED = "acknowledged"
    const val RESOLVED = "resolved"
    val ALL: List<String> = listOf(OPEN, ACKNOWLEDGED, RESOLVED)
}

object AlertActions {
    const val ACKNOWLEDGE = "acknowledge"
    const val RESOLVE = "resolve"
    val ALL: List<String> = listOf(ACKNOWLEDGE, RESOLVE)

    /** Длина комментария подтверждения и закрытия (реш. 413). */
    const val COMMENT_MAX = 500
}
