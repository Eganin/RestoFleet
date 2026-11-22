package ru.restofleet.console.feature.provisioning.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.network.apiCall
import ru.restofleet.console.feature.provisioning.domain.DeviceRole
import ru.restofleet.console.feature.provisioning.domain.IssuedCode
import ru.restofleet.console.feature.provisioning.domain.ProvisioningRepository
import ru.restofleet.console.feature.provisioning.domain.ProvisioningRequest
import ru.restofleet.console.feature.provisioning.domain.RestaurantChoice
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.map
import ru.restofleet.shared.protocol.rest.console.ConsolePaths
import ru.restofleet.shared.protocol.rest.console.IssueTokenRequestDto
import ru.restofleet.shared.protocol.rest.console.IssuedTokenDto
import ru.restofleet.shared.protocol.rest.console.ItemsDto
import ru.restofleet.shared.protocol.rest.console.RestaurantDto

class ProvisioningApi(private val client: HttpClient) {
    suspend fun restaurants(): Outcome<ItemsDto<RestaurantDto>, ConsoleError> = apiCall { client.get(ConsolePaths.RESTAURANTS) }

    suspend fun issue(request: IssueTokenRequestDto): Outcome<IssuedTokenDto, ConsoleError> =
        apiCall { client.post(ConsolePaths.PROVISIONING_TOKENS) { setBody(request) } }
}

class ProvisioningRepositoryImpl(private val api: ProvisioningApi) : ProvisioningRepository {
    override suspend fun restaurants(): Outcome<List<RestaurantChoice>, ConsoleError> = api.restaurants().map { dto ->
        dto.items.sortedBy { it.name }.map { RestaurantChoice(it.id, "${it.name} · ${it.code}") }
    }

    override suspend fun issue(request: ProvisioningRequest): Outcome<IssuedCode, ConsoleError> =
        api.issue(IssueTokenRequestDto(request.restaurantId, request.role.wire, request.name)).map {
            IssuedCode(
                id = it.id,
                code = it.code,
                restaurantId = it.restaurantId,
                role = DeviceRole.entries.firstOrNull { r -> r.wire == it.role } ?: request.role,
                name = it.name,
                expiresAt = it.expiresAt,
            )
        }
}
