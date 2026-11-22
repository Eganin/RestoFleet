package ru.restofleet.shared.protocol.rest.console

import kotlinx.serialization.Serializable

/**
 * Сводный инцидент доступности (FR-BE-35…37; реш. 409–412, 416).
 * - [kind] — [IncidentKinds], [ruleId] — соответствующее правило `R-07…R-09`; [state] — `open | resolved`;
 * - [target] — цель, по которой классифицирован (`api`; у сети ресторана — `ref` или `api`);
 * - [restaurants] — затронутые рестораны (у R-08 — весь список `A`);
 * - [resolveReason] — [IncidentKinds.RESOLVE_CLEARED] или [IncidentKinds.RESOLVE_SUPERSEDED]; закрывается только
 *   автоматически, подтвердить можно с комментарием;
 * - [suppressedAlerts] — сколько тревог устройств он покрывает (их `parentIncidentId` = [id]).
 */
@Serializable
data class IncidentDto(
    val id: String,
    val kind: String,
    val ruleId: String,
    val state: String,
    val target: String,
    val restaurants: List<RestaurantRefDto>,
    val startedAt: Long,
    val resolvedAt: Long? = null,
    val resolveReason: String? = null,
    val suppressedAlerts: Int,
    val ackBy: String? = null,
    val ackAt: Long? = null,
    val comment: String? = null,
)

/** `GET /incidents/{id}`: инцидент и доказательства классификации; текст «Почему так решили» строит консоль. */
@Serializable
data class IncidentDetailsDto(
    val incident: IncidentDto,
    val evidence: IncidentEvidenceDto,
)

/**
 * Снимок агрегатора на тике [evaluatedAt], по которому принято (или последний раз подтверждено) решение.
 * [otherRestaurantsOk] — сколько ресторанов со свежими данными по цели в норме.
 */
@Serializable
data class IncidentEvidenceDto(
    val evaluatedAt: Long,
    val restaurants: List<RestaurantEvidenceDto>,
    val otherRestaurantsOk: Int,
    val thresholds: IncidentThresholdsDto,
)

/**
 * Ресторан в окне агрегатора (реш. 410, 411). [devicesTotal] — учитываемые устройства (без выведенных и молчащих
 * дольше 24 ч), [devicesOnline] — из них онлайн. [targetOnline] и [refOnline] — онлайн-устройства со свежими
 * данными по цели и по эталону `ref`; [targetDown] и [refUp] — сколько из них видят цель недоступной и эталон
 * доступным. [errorClasses] — число проверок цели по классу ошибки `shared:probes` (`timeout`, `dns_fail`,
 * `conn_refused`, `tls_error`, `http_4xx`, `http_5xx`, …).
 */
@Serializable
data class RestaurantEvidenceDto(
    val id: String,
    val code: String,
    val name: String,
    val devicesTotal: Int,
    val devicesOnline: Int,
    val targetOnline: Int,
    val targetDown: Int,
    val refOnline: Int,
    val refUp: Int,
    val errorClasses: Map<String, Int> = emptyMap(),
)

/** Пороги классификатора (реш. 409): [p] — % онлайн-устройств, [m] — `max(3, ⌈0,25·n⌉)`, [n] — ресторанов с данными. */
@Serializable
data class IncidentThresholdsDto(val p: Int, val m: Int, val n: Int)

/** `PATCH /incidents/{id}`: только `acknowledge`, [comment] необязателен (≤ 500); повтор — 409. */
@Serializable
data class IncidentActionDto(val action: String, val comment: String? = null)

object IncidentKinds {
    const val RESTAURANT_NETWORK = "restaurant_network"
    const val BACKEND_GLOBAL = "backend_global"
    const val BACKEND_LOCAL = "backend_local"
    val RULE_BY_KIND: Map<String, String> = mapOf(
        RESTAURANT_NETWORK to RuleIds.R07,
        BACKEND_GLOBAL to RuleIds.R08,
        BACKEND_LOCAL to RuleIds.R09,
    )

    const val STATE_OPEN = "open"
    const val STATE_RESOLVED = "resolved"

    /** Условие держится два тика подряд не выполненным (реш. 412). */
    const val RESOLVE_CLEARED = "cleared"

    /** Смена «локально ↔ глобально»: прежний закрыт, дочерние тревоги перенесены в новый (реш. 412). */
    const val RESOLVE_SUPERSEDED = "superseded"
}
