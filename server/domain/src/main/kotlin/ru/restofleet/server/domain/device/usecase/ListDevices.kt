package ru.restofleet.server.domain.device.usecase

import ru.restofleet.server.domain.alert.AlertRepository
import ru.restofleet.server.domain.alert.activeOrder
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceFilter
import ru.restofleet.server.domain.device.DeviceOverview
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.ingest.TelemetryRepository
import ru.restofleet.server.domain.presence.SessionRepository
import ru.restofleet.server.domain.restaurant.RestaurantRepository

/**
 * `GET /devices` (FR-CN-04, реш. 437): страница устройств и для каждого — ресторан, последняя телеметрия, причина
 * закрытия последней сессии и незакрытые неподавленные тревоги. Дополнительные данные — пакетными запросами по
 * странице, а не по устройству.
 */
class ListDevices(
    private val devices: DeviceRepository,
    private val restaurants: RestaurantRepository,
    private val telemetry: TelemetryRepository,
    private val sessions: SessionRepository,
    private val alerts: AlertRepository,
    private val tx: TransactionRunner,
) {
    suspend operator fun invoke(filter: DeviceFilter, page: PageRequest = PageRequest.of()): List<DeviceOverview> =
        tx.inTransaction {
            val page = devices.list(filter, page)
            if (page.isEmpty()) return@inTransaction emptyList()
            val ids = page.map { it.id }
            val byRestaurant = restaurants.list().associateBy { it.id }
            val latest = telemetry.latestFor(ids)
            val lastSessions = sessions.latestFor(ids)
            val alertsByDevice = alerts.active(ids).filter { it.parentIncidentId == null }.groupBy { it.deviceId }
            page.map { device ->
                DeviceOverview(
                    device = device,
                    restaurant = byRestaurant[device.restaurantId],
                    lastTelemetry = latest[device.id],
                    lastSessionReason = lastSessions[device.id]?.takeUnless { it.isOpen }?.reason,
                    alerts = alertsByDevice[device.id].orEmpty().sortedWith(activeOrder),
                )
            }
        }
}
