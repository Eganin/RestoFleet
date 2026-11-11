package ru.restofleet.server.simulator.data.rest

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.restofleet.server.simulator.data.protocol.ProtocolMapper
import ru.restofleet.server.simulator.domain.model.CommandLoad
import ru.restofleet.server.simulator.domain.model.DeviceCredentials
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.port.ControlApi
import ru.restofleet.server.simulator.domain.port.ProvisionOutcome
import ru.restofleet.server.simulator.domain.port.ProvisioningApi
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.rest.ProvisionRequestDto
import ru.restofleet.shared.protocol.rest.ProvisionResponseDto
import java.io.IOException

/**
 * REST API бэкенда от имени `admin` (контракт K2 «Симулятор»): вход `POST /auth/login` (пароль читает [password] из
 * файла в момент входа, в логи и argv не попадает), access JWT в памяти; ответ 401 — один повторный вход.
 * Сетевая ошибка и неожиданный код — `null`/исключение по смыслу метода.
 */
class OkHttpControlApi(
    client: OkHttpClient,
    apiBase: String,
    private val login: String,
    private val password: () -> String,
) : ControlApi {
    /** Без повтора при обрыве: повтор `POST /commands` создал бы вторую команду. */
    private val client = client.newBuilder().retryOnConnectionFailure(false).build()
    private val root: HttpUrl = apiBase.trimEnd('/').toHttpUrl()
    private val lock = Mutex()

    @Volatile private var token: String? = null

    override suspend fun restaurants(): List<String> {
        val result = authorized {
            url("/api/v1/restaurants").newBuilder().addQueryParameter("limit", "1000").build().get()
        }
        check(result.code == OK) { "GET /restaurants: HTTP ${result.code}" }
        return items(result.body).mapNotNull { it.string("id") }
    }

    override suspend fun issueCodes(restaurantId: String, role: DeviceRole, count: Int): List<String> {
        val body = buildJsonObject {
            put("restaurantId", restaurantId)
            put("role", role.code)
            put("name", "sim-${role.code}")
            put("count", count)
        }
        val result = authorized { url("/api/v1/provisioning/tokens:batch").post(body) }
        check(result.code == CREATED) { "POST /provisioning/tokens:batch: HTTP ${result.code}" }
        return items(result.body).mapNotNull { it.string("code") }
    }

    /** Вход и один авторизованный запрос: токен получен, соединение с бэкендом открыто и лежит в пуле OkHttp. */
    override suspend fun prepare() {
        restaurants()
    }

    override suspend fun sendCommand(deviceId: String, load: CommandLoad): String? {
        val body = buildJsonObject {
            put("type", load.type)
            put("params", JsonObject(emptyMap()))
            put("ttlSec", load.ttlSec)
        }
        val result = optional { authorized { url("/api/v1/devices/$deviceId/commands").post(body) } }
        return result?.takeIf { it.code == CREATED }?.let { parse(it.body)?.string("cmdId") }
    }

    override suspend fun commandStatus(cmdId: String): String? =
        optional { authorized { url("/api/v1/commands/$cmdId").get() } }
            ?.takeIf { it.code == OK }?.let { parse(it.body)?.string("status") }

    override suspend fun deviceStatus(deviceId: String): String? =
        optional { authorized { url("/api/v1/devices/$deviceId").get() } }
            ?.takeIf { it.code == OK }
            ?.let { (parse(it.body)?.get("device") as? JsonObject)?.string("status") }

    private suspend fun authorized(request: () -> Request.Builder): HttpResult {
        val first = client.send(request().header(AUTH, "Bearer ${token ?: login()}").build())
        if (first.code != UNAUTHORIZED) return first
        return client.send(request().header(AUTH, "Bearer ${login(force = true)}").build())
    }

    private suspend fun login(force: Boolean = false): String = lock.withLock {
        token?.takeIf { !force } ?: run {
            val body = buildJsonObject {
                put("login", login)
                put("password", password())
            }
            val result = client.send(url("/api/v1/auth/login").post(body).build())
            check(result.code == OK) { "login failed: HTTP ${result.code}" }
            val access = parse(result.body)?.string("accessToken") ?: error("login: no accessToken in response")
            token = access
            access
        }
    }

    private fun url(path: String): HttpUrl = root.newBuilder().encodedPath(path).build()

    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (_: IOException) {
        null
    }

    private companion object {
        const val AUTH = "Authorization"
        const val OK = 200
        const val CREATED = 201
        const val UNAUTHORIZED = 401
    }
}

/**
 * `POST /provision` (тот же путь, что агент, FR-SM-08): 201 — учётка; 429, 5xx и сетевые ошибки — повторить; 401/409/
 * 410/400 — окончательный отказ с `code` бэкенда. Пароль из ответа уходит только в кэш учёток.
 */
class OkHttpProvisioningApi(
    client: OkHttpClient,
    apiBase: String,
    private val agentVersion: String,
) : ProvisioningApi {
    /** Без повтора при обрыве: код одноразовый, повтор погашенного дал бы `token_used` (как у агента, реш. 269). */
    private val client = client.newBuilder().retryOnConnectionFailure(false).build()
    private val endpoint: HttpUrl =
        apiBase.trimEnd('/').toHttpUrl().newBuilder().encodedPath("/api/v1/provision").build()

    override suspend fun provision(code: String): ProvisionOutcome {
        val body = RfJson.encodeToString(
            ProvisionRequestDto.serializer(), ProvisionRequestDto(code, ProtocolMapper.DEVICE_INFO, agentVersion),
        )
        val result = try {
            client.send(Request.Builder().url(endpoint).post(body.jsonBody()).build())
        } catch (e: IOException) {
            return ProvisionOutcome.Retry("network:${e.javaClass.simpleName}")
        }
        return when {
            result.code == CREATED -> registered(result.body)
            result.code == TOO_MANY || result.code >= SERVER_ERROR -> ProvisionOutcome.Retry("http_${result.code}")
            else -> ProvisionOutcome.Rejected(parse(result.body)?.string("code") ?: "http_${result.code}")
        }
    }

    private fun registered(body: String): ProvisionOutcome {
        val dto = try {
            RfJson.decodeFromString(ProvisionResponseDto.serializer(), body)
        } catch (_: IllegalArgumentException) {
            null
        }
        val role = dto?.let { DeviceRole.fromCode(it.role) }
        return when {
            dto == null -> ProvisionOutcome.Rejected("invalid_response")
            role == null -> ProvisionOutcome.Rejected("invalid_role")
            else -> ProvisionOutcome.Registered(DeviceCredentials(dto.deviceId, dto.password, dto.restaurantId, role))
        }
    }

    private companion object {
        const val CREATED = 201
        const val TOO_MANY = 429
        const val SERVER_ERROR = 500
    }
}

private fun parse(body: String): JsonObject? = try {
    RfJson.parseToJsonElement(body) as? JsonObject
} catch (_: IllegalArgumentException) {
    null
}

private fun items(body: String): List<JsonObject> =
    (parse(body)?.get("items") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun HttpUrl.get(): Request.Builder = Request.Builder().url(this).get()

private fun HttpUrl.post(body: JsonObject): Request.Builder =
    Request.Builder().url(this).post(RfJson.encodeToString(JsonObject.serializer(), body).jsonBody())
