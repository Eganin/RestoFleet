package ru.restofleet.server.data.mqtt

import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.restofleet.server.domain.common.ServerError.BrokerUnavailable
import ru.restofleet.server.domain.device.BrokerAccounts
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.UuidIdGenerator
import ru.restofleet.shared.protocol.RfJson
import java.util.concurrent.ConcurrentHashMap

/**
 * Учётки устройств через Mosquitto Dynamic Security (`$CONTROL/dynamic-security/v1`, FR-BR-04): одна команда на
 * запрос, ответ сопоставляется по `correlationData`, ожидание — [timeoutMs]. Клиент создаётся сразу с ролью
 * `device` и `clientid = deviceId` одной командой `createClient` (P5, P6) — частично созданного клиента не бывает.
 * Отключение и удаление отсутствующего клиента — успех (идемпотентность). Пароль в лог не попадает.
 */
class DynSecBrokerAccounts(
    private val client: HiveMqClient,
    private val timeoutMs: Long,
    private val ids: IdGenerator = UuidIdGenerator(),
) : BrokerAccounts {
    private val log = KotlinLogging.logger {}
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    init {
        client.onControlMessage { topic, payload -> if (topic == RESPONSE_TOPIC) onResponse(payload) }
    }

    override suspend fun createDevice(id: DeviceId, password: String): Outcome<Unit, BrokerUnavailable> =
        execute("createClient", tolerateNotFound = false) {
            put("username", id.value)
            put("password", password)
            put("clientid", id.value)
            put("roles", JsonArray(listOf(buildJsonObject { put("rolename", DEVICE_ROLE) })))
        }

    override suspend fun disableDevice(id: DeviceId): Outcome<Unit, BrokerUnavailable> =
        execute("disableClient", tolerateNotFound = true) { put("username", id.value) }

    override suspend fun deleteDevice(id: DeviceId): Outcome<Unit, BrokerUnavailable> =
        execute("deleteClient", tolerateNotFound = true) { put("username", id.value) }

    private suspend fun execute(
        command: String,
        tolerateNotFound: Boolean,
        fields: JsonObjectBuilder.() -> Unit,
    ): Outcome<Unit, BrokerUnavailable> {
        val correlation = ids.newId()
        val waiter = CompletableDeferred<JsonObject>()
        pending[correlation] = waiter
        val problem = try {
            client.publish(request(command, correlation, fields))?.let { "not sent ($it)" }
                ?: responseError(waiter, tolerateNotFound)
        } finally {
            pending.remove(correlation)
        }
        return if (problem == null) {
            Outcome.Success(Unit)
        } else {
            // В причине — код ответа DynSec или транспорта, не тело запроса (в нём пароль)
            log.warn { "DynSec $command failed: $problem" }
            Outcome.Failure(BrokerUnavailable(OPERATIONS[command] ?: command))
        }
    }

    private fun request(command: String, correlation: String, fields: JsonObjectBuilder.() -> Unit): Mqtt5Publish {
        val body = buildJsonObject {
            put(
                "commands",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("command", command)
                            fields()
                            put("correlationData", correlation)
                        },
                    ),
                ),
            )
        }
        return Mqtt5Publish.builder()
            .topic(CONTROL_TOPIC)
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(RfJson.encodeToString(JsonObject.serializer(), body).toByteArray())
            .build()
    }

    /** `null` — команда выполнена (или «не найдено» там, где это успех). */
    private suspend fun responseError(waiter: CompletableDeferred<JsonObject>, tolerateNotFound: Boolean): String? {
        val response = withTimeoutOrNull(timeoutMs) { waiter.await() } ?: return "no response in $timeoutMs ms"
        val error = (response["error"] as? JsonPrimitive)?.content
        return error?.takeUnless { tolerateNotFound && it.contains(NOT_FOUND, ignoreCase = true) }
    }

    private fun onResponse(payload: ByteArray) {
        val responses = try {
            RfJson.parseToJsonElement(payload.decodeToString()).jsonObject["responses"]?.jsonArray.orEmpty()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            log.warn { "unreadable DynSec response: ${e.javaClass.simpleName}" }
            return
        }
        responses.forEach { item: JsonElement ->
            val response = item as? JsonObject ?: return@forEach
            val correlation = response["correlationData"]?.jsonPrimitive?.content ?: return@forEach
            pending[correlation]?.complete(response)
        }
    }

    companion object {
        const val CONTROL_TOPIC = "\$CONTROL/dynamic-security/v1"
        const val RESPONSE_TOPIC = "\$CONTROL/dynamic-security/v1/response"
        const val DEVICE_ROLE = "device"
        private const val NOT_FOUND = "not found"
        private val OPERATIONS = mapOf(
            "createClient" to "create_client",
            "disableClient" to "disable_client",
            "deleteClient" to "delete_client",
        )
    }
}
