package ru.restofleet.console.feature.devices.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.apiCall
import ru.restofleet.console.feature.devices.domain.DeviceFilter
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.rest.console.ConsolePaths
import ru.restofleet.shared.protocol.rest.console.DeviceDto
import ru.restofleet.shared.protocol.rest.console.ItemsDto
import ru.restofleet.shared.protocol.rest.console.RestaurantDto

class DevicesApi(private val client: HttpClient) {
    suspend fun devices(filter: DeviceFilter, offset: Int, limit: Int): Outcome<ItemsDto<DeviceDto>, ConsoleError> =
        apiCall {
            client.get(ConsolePaths.DEVICES) {
                filter.restaurantId?.let { parameter("restaurant", it) }
                filter.role?.let { parameter("role", it) }
                filter.status?.let { parameter("status", it) }
                filter.version?.let { parameter("version", it) }
                filter.query.trim().takeIf { it.isNotEmpty() }?.let { parameter("q", it) }
                if (filter.problemsOnly) parameter("problems", true)
                parameter("limit", limit)
                parameter("offset", offset)
            }
        }

    suspend fun restaurants(): Outcome<ItemsDto<RestaurantDto>, ConsoleError> =
        apiCall { client.get(ConsolePaths.RESTAURANTS) }
}
