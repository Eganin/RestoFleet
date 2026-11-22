package ru.restofleet.console.feature.devicedetails.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.apiCall
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.rest.console.ConsolePaths
import ru.restofleet.shared.protocol.rest.console.DeviceDetailsDto
import ru.restofleet.shared.protocol.rest.console.EventRecordDto
import ru.restofleet.shared.protocol.rest.console.ItemsDto
import ru.restofleet.shared.protocol.rest.console.ProbeRecordDto
import ru.restofleet.shared.protocol.rest.console.TelemetryPointDto

class DeviceDetailsApi(private val client: HttpClient) {
    suspend fun details(id: String): Outcome<DeviceDetailsDto, ConsoleError> =
        apiCall { client.get(ConsolePaths.device(id)) }

    suspend fun telemetry(id: String, from: Long, limit: Int): Outcome<ItemsDto<TelemetryPointDto>, ConsoleError> =
        apiCall {
            client.get(ConsolePaths.deviceTelemetry(id)) {
                parameter("from", from)
                parameter("metric", "tempC")
                parameter("limit", limit)
            }
        }

    suspend fun probes(id: String, limit: Int): Outcome<ItemsDto<ProbeRecordDto>, ConsoleError> =
        apiCall { client.get(ConsolePaths.deviceProbes(id)) { parameter("limit", limit) } }

    suspend fun events(id: String, offset: Int, limit: Int): Outcome<ItemsDto<EventRecordDto>, ConsoleError> =
        apiCall {
            client.get(ConsolePaths.deviceEvents(id)) {
                parameter("limit", limit)
                parameter("offset", offset)
            }
        }
}
