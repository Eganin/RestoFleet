package ru.restofleet.server.api.provisioning

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.restofleet.server.api.ProvisioningUseCases
import ru.restofleet.server.api.error.ApiException
import ru.restofleet.server.api.error.orThrow
import ru.restofleet.server.api.http.noStore
import ru.restofleet.server.api.http.requireText
import ru.restofleet.server.domain.device.DeviceInfo
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.provisioning.IssueTokensRequest
import ru.restofleet.server.domain.provisioning.IssuedToken
import ru.restofleet.server.domain.provisioning.ProvisionRequest
import ru.restofleet.server.domain.provisioning.ProvisionedDevice
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.protocol.rest.MqttEndpointDto
import ru.restofleet.shared.protocol.rest.ProvisionRequestDto
import ru.restofleet.shared.protocol.rest.ProvisionResponseDto
import ru.restofleet.shared.protocol.rest.console.IssueTokenRequestDto
import ru.restofleet.shared.protocol.rest.console.IssueTokensBatchRequestDto
import ru.restofleet.shared.protocol.rest.console.IssuedTokenDto
import ru.restofleet.shared.protocol.rest.console.ItemsDto

/** Выпуск кодов (admin JWT). Ответ несёт секреты — `Cache-Control: no-store`. */
fun Route.tokenRoutes(useCases: ProvisioningUseCases) {
    post("/provisioning/tokens") {
        val request = call.receive<IssueTokenRequestDto>()
        val issued = useCases.issue(issue(request.restaurantId, request.role, request.name, 1)).orThrow()
        call.noStore()
        call.respond(HttpStatusCode.Created, issued.single().toDto())
    }
    post("/provisioning/tokens:batch") {
        val request = call.receive<IssueTokensBatchRequestDto>()
        val issued = useCases.issue(issue(request.restaurantId, request.role, request.name, request.count)).orThrow()
        call.noStore()
        call.respond(HttpStatusCode.Created, ItemsDto(issued.map { it.toDto() }))
    }
}

/**
 * Обмен кода на учётку устройства (FR-BE-02, без JWT, под rate limit): тело и ответ — REST-контракт агента
 * `shared:protocol.rest` (реш. 222). Пароль отдаётся один раз — `Cache-Control: no-store`. Ошибки кода:
 * `token_invalid` 401, `token_used` 409, `token_expired` 410 (реш. 247).
 */
fun Route.provisionRoute(useCases: ProvisioningUseCases) {
    post("/provision") {
        val request = call.receive<ProvisionRequestDto>().toDomain()
        // bcrypt пароля и запросы к брокеру — не на потоках вызовов Netty
        val provisioned = withContext(Dispatchers.Default) { useCases.provision(request) }.orThrow()
        call.noStore()
        call.respond(HttpStatusCode.Created, provisioned.toDto())
    }
}

private const val MAX_CODE = 64
private const val MAX_MODEL = 100
private const val MAX_OS = 32
private const val MAX_VERSION = 64
private val API_LEVEL = 1..1_000

private fun issue(restaurantId: String, role: String, name: String?, count: Int) = IssueTokensRequest(
    restaurantId = RestaurantId(requireText("restaurantId", restaurantId, MAX_CODE)),
    role = DeviceRole.fromCode(role) ?: throw ApiException.validation("role", "unknown"),
    name = name?.let { requireText("name", it, MAX_MODEL) },
    count = count,
)

private fun IssuedToken.toDto() = IssuedTokenDto(id, code, restaurantId.value, role.code, name, expiresAt)

private fun ProvisionRequestDto.toDomain(): ProvisionRequest {
    val info = deviceInfo
    if (info.apiLevel !in API_LEVEL) throw ApiException.validation("deviceInfo.apiLevel", "out_of_range")
    return ProvisionRequest(
        // Код только хэшируется (в БД не пишется): неверный любой длины — `token_invalid` от домена
        code = code,
        deviceInfo = DeviceInfo(
            model = requireText("deviceInfo.model", info.model, MAX_MODEL),
            manufacturer = requireText("deviceInfo.manufacturer", info.manufacturer, MAX_MODEL),
            osVersion = requireText("deviceInfo.osVersion", info.osVersion, MAX_OS),
            apiLevel = info.apiLevel,
        ),
        agentVersion = requireText("agentVersion", agentVersion, MAX_VERSION),
    )
}

private fun ProvisionedDevice.toDto() = ProvisionResponseDto(
    deviceId = deviceId.value,
    password = password,
    restaurantId = restaurantId.value,
    role = role.code,
    mqtt = MqttEndpointDto(broker.host, broker.port),
)
