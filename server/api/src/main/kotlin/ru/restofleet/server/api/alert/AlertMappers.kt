package ru.restofleet.server.api.alert

import ru.restofleet.server.domain.alert.Alert
import ru.restofleet.server.domain.alert.AlertView
import ru.restofleet.server.domain.alert.ProblemRestaurant
import ru.restofleet.server.domain.alert.Summary
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.shared.protocol.rest.console.AlertDto
import ru.restofleet.shared.protocol.rest.console.ProblemRestaurantDto
import ru.restofleet.shared.protocol.rest.console.RestaurantRefDto
import ru.restofleet.shared.protocol.rest.console.SummaryDto

// Маппинг домена в REST-DTO консоли (реш. 408): DTO — в shared:protocol `rest.console`

internal fun AlertView.toDto() = alert.toDto(deviceName, restaurantCode, restaurantName)

internal fun Alert.toDto(deviceName: String?, restaurantCode: String, restaurantName: String) = AlertDto(
    id = id,
    ruleId = rule.code,
    severity = severity.code,
    state = state.code,
    deviceId = deviceId.value,
    deviceName = deviceName,
    restaurantId = restaurantId.value,
    restaurantCode = restaurantCode,
    restaurantName = restaurantName,
    target = subject.target,
    openedAt = openedAt,
    ackBy = ackBy,
    ackAt = ackAt,
    resolvedAt = resolvedAt,
    resolvedBy = resolvedBy,
    comment = comment,
    auto = auto,
    parentIncidentId = parentIncidentId,
    details = details,
)

internal fun Restaurant.toRef() = RestaurantRefDto(id.value, code, name)

internal fun Summary.toDto() = SummaryDto(
    devicesOnline = devicesOnline,
    devicesTotal = devicesTotal,
    devicesOffline = devicesOffline,
    offlineLastHour = offlineLastHour,
    alertsOpen = alertsOpen,
    alertsCritical = alertsCritical,
    incidentsOpen = incidentsOpen,
    restaurantsWithProblems = restaurantsWithProblems,
    restaurantsTotal = restaurantsTotal,
    problemRestaurants = problems.map { it.toDto() },
)

private fun ProblemRestaurant.toDto() = ProblemRestaurantDto(
    restaurant = restaurant.toRef(),
    ruleId = rule.code,
    count = count,
    devicesTotal = devicesTotal,
    target = target,
    deviceId = deviceId?.value,
    deviceName = deviceName,
    since = since,
    details = details,
)
