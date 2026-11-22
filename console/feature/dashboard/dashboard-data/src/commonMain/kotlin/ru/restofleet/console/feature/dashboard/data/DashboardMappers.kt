package ru.restofleet.console.feature.dashboard.data

import ru.restofleet.console.feature.dashboard.domain.ProblemRestaurant
import ru.restofleet.console.feature.dashboard.domain.RecentAlert
import ru.restofleet.console.feature.dashboard.domain.Summary
import ru.restofleet.shared.protocol.rest.console.AlertDto
import ru.restofleet.shared.protocol.rest.console.ProblemRestaurantDto
import ru.restofleet.shared.protocol.rest.console.Severities
import ru.restofleet.shared.protocol.rest.console.SummaryDto

internal fun SummaryDto.toDomain() = Summary(
    devicesOnline = devicesOnline,
    devicesTotal = devicesTotal,
    devicesOffline = devicesOffline,
    offlineLastHour = offlineLastHour,
    alertsOpen = alertsOpen,
    alertsCritical = alertsCritical,
    restaurantsWithProblems = restaurantsWithProblems,
    restaurantsTotal = restaurantsTotal,
    problems = problemRestaurants.map { it.toDomain() },
)

internal fun ProblemRestaurantDto.toDomain() = ProblemRestaurant(
    restaurantId = restaurant.id,
    code = restaurant.code,
    name = restaurant.name,
    ruleId = ruleId,
    count = count,
    devicesTotal = devicesTotal,
    target = target,
    deviceId = deviceId,
    deviceName = deviceName,
    since = since,
    details = details,
)

internal fun AlertDto.toRecent() = RecentAlert(
    id = id,
    ruleId = ruleId,
    critical = severity == Severities.CRITICAL,
    deviceId = deviceId,
    deviceName = deviceName,
    restaurantName = restaurantName,
    target = target,
    openedAt = openedAt,
    details = details,
)
