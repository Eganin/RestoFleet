package ru.restofleet.server.domain.alert

import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.shared.kernel.IdGenerator

/**
 * Правила в транзакции приёма (реш. 414): `IngestBatch` и `HandleStatus` зовут [process] до commit, поэтому ack
 * брокеру после commit покрывает и тревоги, а повторная доставка ничего не дублирует (факты-условия и
 * дедупликация). Входы одного вызова обрабатываются по времени; выведенные и неизвестные устройства пропускаются.
 */
class AlertProcessor(
    private val alerts: AlertRepository,
    private val conditions: AlertConditionRepository,
    private val devices: DeviceRepository,
    private val ids: IdGenerator,
    private val params: RuleParams,
) {
    /** Идентификаторы открытых и закрытых этим вызовом тревог — их публикуют после commit. */
    suspend fun process(inputs: List<RuleInput>): List<String> {
        if (inputs.isEmpty()) return emptyList()
        val deviceIds = inputs.map { it.deviceId }.toSet()
        val restaurants = devices.findByIds(deviceIds).filterNot { it.isDecommissioned }
            .associate { it.id to it.restaurantId }
        val initial = conditions.active(restaurants.keys).associateBy { it.key }
        var current = initial
        val open = alerts.active(restaurants.keys).toMutableList()
        val changed = linkedSetOf<String>()
        inputs.sortedBy { it.ts }.forEach { input ->
            val restaurantId = restaurants[input.deviceId] ?: return@forEach
            val outcome = RuleEngine.evaluate(input, current, open, params)
            current = outcome.conditions
            outcome.changes.forEach { change ->
                when (change) {
                    is AlertChange.Open -> {
                        val alert = Alert(
                            id = ids.newId(), subject = change.subject, restaurantId = restaurantId,
                            state = AlertState.OPEN, openedAt = change.at, details = change.details,
                        )
                        alerts.insert(alert)
                        open += alert
                        changed += alert.id
                    }
                    is AlertChange.AutoResolve -> {
                        val index = open.indexOfFirst { it.id == change.alertId }
                        val resolved =
                            open[index].copy(state = AlertState.RESOLVED, resolvedAt = change.at, auto = true)
                        alerts.update(resolved)
                        open[index] = resolved
                        changed += resolved.id
                    }
                }
            }
        }
        (initial.keys - current.keys).forEach { conditions.delete(it) }
        current.values.filter { initial[it.key] != it }.forEach { conditions.upsert(it) }
        return changed.toList()
    }
}
