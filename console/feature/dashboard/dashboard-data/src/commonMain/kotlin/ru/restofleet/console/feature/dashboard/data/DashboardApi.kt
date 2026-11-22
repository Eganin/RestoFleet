package ru.restofleet.console.feature.dashboard.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.apiCall
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.rest.console.AlertDto
import ru.restofleet.shared.protocol.rest.console.ConsolePaths
import ru.restofleet.shared.protocol.rest.console.ItemsDto
import ru.restofleet.shared.protocol.rest.console.SummaryDto

class DashboardApi(private val client: HttpClient) {
    suspend fun summary(): Outcome<SummaryDto, ConsoleError> = apiCall { client.get(ConsolePaths.SUMMARY) }

    suspend fun recentAlerts(limit: Int): Outcome<ItemsDto<AlertDto>, ConsoleError> = apiCall {
        client.get(ConsolePaths.ALERTS) { parameter("limit", limit) }
    }
}
