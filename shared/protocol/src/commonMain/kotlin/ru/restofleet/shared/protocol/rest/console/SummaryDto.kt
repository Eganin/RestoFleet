package ru.restofleet.shared.protocol.rest.console

import kotlinx.serialization.Serializable

/**
 * `GET /summary` и WS `summary_changed` (FR-CN-02). Устройства — без выведенных из эксплуатации; тревоги — открытые
 * и подтверждённые, без подавленных инцидентом. [offlineLastHour] — ушли в офлайн за последний час.
 * [problemRestaurants] — не больше 10, сначала рестораны с инцидентом, затем по числу критичных тревог.
 */
@Serializable
data class SummaryDto(
    val devicesOnline: Int,
    val devicesTotal: Int,
    val devicesOffline: Int,
    val offlineLastHour: Int,
    val alertsOpen: Int,
    val alertsCritical: Int,
    val incidentsOpen: Int,
    val restaurantsWithProblems: Int,
    val restaurantsTotal: Int,
    val problemRestaurants: List<ProblemRestaurantDto> = emptyList(),
)

/**
 * Главная проблема ресторана: правило ([ruleId] — тревоги или инцидента), сколько устройств затронуто [count] из
 * [devicesTotal], с какого момента [since]. При [count] = 1 — устройство ([deviceId], [deviceName]); [target] — у
 * R-05 и инцидентов; [details] — как `AlertDto.details`. Текст строки строит консоль.
 */
@Serializable
data class ProblemRestaurantDto(
    val restaurant: RestaurantRefDto,
    val ruleId: String,
    val count: Int,
    val devicesTotal: Int,
    val target: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val since: Long,
    val details: Map<String, String> = emptyMap(),
)
