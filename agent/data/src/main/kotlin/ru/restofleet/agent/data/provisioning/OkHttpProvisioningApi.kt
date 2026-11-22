package ru.restofleet.agent.data.provisioning

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.restofleet.agent.domain.error.ProvisionError
import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.agent.domain.model.ProvisionedDevice
import ru.restofleet.agent.domain.port.ProvisioningApi
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.DeviceInfoDto
import ru.restofleet.shared.protocol.rest.ProvisionRequestDto
import ru.restofleet.shared.protocol.rest.ProvisionResponseDto
import ru.restofleet.shared.protocol.rest.RestPaths
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Провижининг по REST (FR-AG-10, контракт K2): `POST {baseUrl}/api/v1/provision` с [ProvisionRequestDto] (код,
 * сведения об устройстве [device], версия агента). Ответ 201 — [ProvisionResponseDto], проверяется до сохранения:
 * `deviceId` годится в топики, роль известна, адрес брокера — имя или IP без схемы, порт 1..65535, пароль непустой.
 * Отказ бэкенда — по его `code` (реш. 247), без кода — по HTTP-статусу (502 nginx без бэкенда — `server_unavailable`).
 * Сеть: TLS — `tls_error`, таймаут — `timeout`, прочий ввод-вывод — `no_network`. Код и пароль не логируются и в
 * ошибки не попадают; повтор запроса при обрыве выключен у [baseClient] (код одноразовый).
 */
class OkHttpProvisioningApi(
    private val baseClient: OkHttpClient,
    private val trust: ProvisioningTrust,
    private val device: DeviceInfoDto,
    private val agentVersion: String,
    private val dispatcher: CoroutineDispatcher,
) : ProvisioningApi {
    override suspend fun provision(baseUrl: String, code: String): Outcome<ProvisionedDevice, ProvisionError> =
        withContext(dispatcher) {
            val url = baseUrl.toHttpUrlOrNull()?.newBuilder()?.addPathSegments(RestPaths.PROVISION.removePrefix("/"))
                ?.build()
            val client = trust.client(baseClient)
            when {
                url == null -> Outcome.Failure(ProvisionError.InvalidBaseUrl)
                client is Outcome.Failure -> client
                else -> send((client as Outcome.Success).value, request(url.toString(), code))
            }
        }

    private fun request(url: String, code: String): Request {
        val dto = ProvisionRequestDto(code, device, agentVersion)
        val body = RfJson.encodeToString(ProvisionRequestDto.serializer(), dto)
        return Request.Builder()
            .url(url)
            .header("Accept", JSON)
            .post(body.toRequestBody(JSON.toMediaType()))
            .build()
    }

    private suspend fun send(client: OkHttpClient, request: Request): Outcome<ProvisionedDevice, ProvisionError> =
        try {
            client.newCall(request).await().use { response ->
                if (response.code == HTTP_CREATED || response.code == HTTP_OK) {
                    parseSuccess(response.body.string())
                } else {
                    Outcome.Failure(refusal(response))
                }
            }
        } catch (e: IOException) {
            Outcome.Failure(classify(e))
        }

    private fun decode(body: String): ProvisionResponseDto? = try {
        RfJson.decodeFromString(ProvisionResponseDto.serializer(), body)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseSuccess(body: String): Outcome<ProvisionedDevice, ProvisionError> {
        val dto = decode(body) ?: return Outcome.Failure(ProvisionError.InvalidResponse)
        val id = DeviceId.parse(dto.deviceId)
        val role = DeviceRole.fromCode(dto.role)
        val valid = id != null && role != null && HOST.matches(dto.mqtt.host) && dto.mqtt.port in PORTS &&
            dto.password.isNotEmpty() && dto.restaurantId.isNotBlank()
        return if (valid) {
            Outcome.Success(ProvisionedDevice(id, dto.password, dto.restaurantId, role, dto.mqtt.host, dto.mqtt.port))
        } else {
            Outcome.Failure(ProvisionError.InvalidResponse)
        }
    }

    private fun refusal(response: Response): ProvisionError {
        val serverCode = errorCode(response)
        return when {
            serverCode == "token_invalid" -> ProvisionError.CodeInvalid
            serverCode == "token_used" -> ProvisionError.CodeUsed
            serverCode == "token_expired" -> ProvisionError.CodeExpired
            serverCode == "rate_limited" || response.code == HTTP_TOO_MANY ->
                ProvisionError.RateLimited(response.header("Retry-After")?.trim()?.toLongOrNull())
            serverCode in UNAVAILABLE || response.code >= HTTP_SERVER_ERROR -> ProvisionError.ServerUnavailable
            else -> ProvisionError.Rejected(serverCode ?: "http_${response.code}")
        }
    }

    /** `code` из тела `{code, message}` (реш. 247); тело без JSON (страница nginx) — `null`. */
    private fun errorCode(response: Response): String? = try {
        val json = RfJson.parseToJsonElement(response.body.string()) as? JsonObject
        (json?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { CODE.matches(it) }
    } catch (_: SerializationException) {
        null
    } catch (_: IOException) {
        null
    }

    /**
     * Причины и подавленные исключения тоже: OkHttp перебирает адреса хоста (IPv4 и IPv6 у `localhost`) и бросает сбой
     * последнего маршрута, а отказ TLS первого лежит в `suppressed`.
     */
    private fun classify(e: IOException): ProvisionError {
        val chain = generateSequence<Throwable>(e) { it.cause }.take(MAX_CAUSE_DEPTH)
            .flatMap { sequenceOf(it) + it.suppressed.asSequence() }
            .toList()
        return when {
            chain.any { it is SSLException || it is CertificateException } -> ProvisionError.Tls
            chain.any { it is SocketTimeoutException || (it is InterruptedIOException && it.message == TIMEOUT) } ->
                ProvisionError.Timeout
            else -> ProvisionError.NoNetwork
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    private companion object {
        const val JSON = "application/json"
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_TOO_MANY = 429
        const val HTTP_SERVER_ERROR = 500
        const val TIMEOUT = "timeout"
        const val MAX_CAUSE_DEPTH = 8
        val UNAVAILABLE = setOf("broker_unavailable", "unavailable")
        val PORTS = 1..65_535
        val HOST = Regex("^[A-Za-z0-9._:-]{1,253}$")
        val CODE = Regex("^[a-z0-9_]{1,64}$")
    }
}
