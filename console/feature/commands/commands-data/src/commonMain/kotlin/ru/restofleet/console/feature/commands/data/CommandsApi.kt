package ru.restofleet.console.feature.commands.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.apiCall
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.rest.console.CommandDto
import ru.restofleet.shared.protocol.rest.console.ConsolePaths
import ru.restofleet.shared.protocol.rest.console.DeviceDetailsDto
import ru.restofleet.shared.protocol.rest.console.ItemsDto
import ru.restofleet.shared.protocol.rest.console.SendCommandRequestDto

class CommandsApi(private val client: HttpClient) {
    suspend fun device(id: String): Outcome<DeviceDetailsDto, ConsoleError> = apiCall { client.get(ConsolePaths.device(id)) }

    suspend fun send(id: String, type: String, params: Map<String, String>): Outcome<CommandDto, ConsoleError> = apiCall {
        client.post(ConsolePaths.deviceCommands(id)) {
            setBody(SendCommandRequestDto(type, JsonObject(params.mapValues { JsonPrimitive(it.value) })))
        }
    }

    suspend fun history(id: String, limit: Int): Outcome<ItemsDto<CommandDto>, ConsoleError> =
        apiCall { client.get(ConsolePaths.deviceCommands(id)) { parameter("limit", limit) } }

    suspend fun status(cmdId: String): Outcome<CommandDto, ConsoleError> = apiCall { client.get(ConsolePaths.command(cmdId)) }
}
