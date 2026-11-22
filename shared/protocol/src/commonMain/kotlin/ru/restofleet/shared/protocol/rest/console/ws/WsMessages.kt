package ru.restofleet.shared.protocol.rest.console.ws

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import ru.restofleet.shared.protocol.rest.console.AlertDto
import ru.restofleet.shared.protocol.rest.console.IncidentDto
import ru.restofleet.shared.protocol.rest.console.SummaryDto
import ru.restofleet.shared.protocol.rest.console.TelemetryPointDto

/**
 * WebSocket консоли `GET /api/v1/ws` (FR-BE-33, 51; реш. 418, 419). Текстовые фреймы JSON, конверт
 * `{v, type, ts, data}`; клиент шлёт тот же конверт без `ts`.
 *
 * - Аутентификация — только заголовок `Authorization: Bearer` upgrade-запроса; токен в query не принимается.
 * - Всем соединениям: `device_status`, `alert`, `incident` и `summary_changed` (не чаще раза в
 *   [WsLimits.SUMMARY_MIN_INTERVAL_MS] мс).
 * - `telemetry` — только по `subscribe_device`, не больше [WsLimits.MAX_SUBSCRIPTIONS] устройств.
 * - После каждого (пере)подключения клиент перечитывает состояние через REST; `resync` — сервер просит сделать это
 *   без переподключения (события потеряны).
 * - Неизвестный `type` или другая версия `v` декодируются в `Unknown`: клиент их пропускает (совместимость вперёд).
 */
const val WS_PROTOCOL_VERSION = 1

/** Сообщение сервера. Сериализуется только форматом JSON (`RfJson`). */
@Serializable(with = WsServerMessageSerializer::class)
sealed interface WsServerMessage {
    val ts: Long

    data class Hello(override val ts: Long, val data: WsHelloDto) : WsServerMessage
    data class DeviceStatus(override val ts: Long, val data: WsDeviceStatusDto) : WsServerMessage
    data class Telemetry(override val ts: Long, val data: WsTelemetryDto) : WsServerMessage
    data class Alert(override val ts: Long, val data: AlertDto) : WsServerMessage
    data class Incident(override val ts: Long, val data: IncidentDto) : WsServerMessage
    data class SummaryChanged(override val ts: Long, val data: SummaryDto) : WsServerMessage
    data class Resync(override val ts: Long, val data: WsResyncDto) : WsServerMessage
    data class Unknown(val v: Int, val type: String, override val ts: Long, val data: JsonElement) : WsServerMessage
}

/** Сообщение клиента. Нарушение лимитов или неразборчивый фрейм — закрытие [WsCloseCodes.POLICY_VIOLATION]. */
@Serializable(with = WsClientMessageSerializer::class)
sealed interface WsClientMessage {
    data class SubscribeDevice(val data: WsDeviceRefDto) : WsClientMessage
    data class UnsubscribeDevice(val data: WsDeviceRefDto) : WsClientMessage
    data class Unknown(val v: Int, val type: String, val data: JsonElement) : WsClientMessage
}

/** `hello` — первое сообщение после upgrade: кто подключён и когда сервер закроет соединение кодом 4401. */
@Serializable
data class WsHelloDto(
    val connectionId: String,
    val user: String,
    val accessExpiresAt: Long,
    val maxSubscriptions: Int,
)

/** `device_status` — смена статуса устройства (`online`, `offline`, `decommissioned`); `reason` — `lwt`, `stop`. */
@Serializable
data class WsDeviceStatusDto(
    val deviceId: String,
    val restaurantId: String,
    val status: String,
    val lastSeenAt: Long? = null,
    val reason: String? = null,
)

/** `telemetry` — новая точка устройства из подписки. */
@Serializable
data class WsTelemetryDto(
    val deviceId: String,
    val point: TelemetryPointDto,
)

/** `resync` — перечитать состояние через REST. */
@Serializable
data class WsResyncDto(val reason: String) {
    companion object {
        /** Шина событий отбросила события для этого соединения (`DROP_OLDEST`, реш. 419). */
        const val EVENTS_DROPPED = "events_dropped"
    }
}

/** `subscribe_device` и `unsubscribe_device`. */
@Serializable
data class WsDeviceRefDto(val deviceId: String)

object WsTypes {
    const val HELLO = "hello"
    const val DEVICE_STATUS = "device_status"
    const val TELEMETRY = "telemetry"
    const val ALERT = "alert"
    const val INCIDENT = "incident"
    const val SUMMARY_CHANGED = "summary_changed"
    const val RESYNC = "resync"
    const val SUBSCRIBE_DEVICE = "subscribe_device"
    const val UNSUBSCRIBE_DEVICE = "unsubscribe_device"

    val SERVER: Set<String> = setOf(HELLO, DEVICE_STATUS, TELEMETRY, ALERT, INCIDENT, SUMMARY_CHANGED, RESYNC)
    val CLIENT: Set<String> = setOf(SUBSCRIBE_DEVICE, UNSUBSCRIBE_DEVICE)
}

/** Коды закрытия (реш. 418, 419). */
object WsCloseCodes {
    /** Сервер останавливается: переподключиться с backoff. */
    const val GOING_AWAY = 1001

    /** Превышен лимит соединений или подписок, неразборчивый фрейм: ошибка клиента, не переподключаться сразу. */
    const val POLICY_VIOLATION = 1008

    /** Очередь соединения переполнена: переподключиться и перечитать состояние через REST. */
    const val TRY_AGAIN_LATER = 1013

    /** Истёк access-токен (`exp`): обновить пару через refresh и переподключиться. */
    const val TOKEN_EXPIRED = 4401
}

/** Лимиты сервера (реш. 419, план 3.5). */
object WsLimits {
    const val MAX_CONNECTIONS_PER_USER = 4
    const val MAX_SUBSCRIPTIONS = 5
    const val QUEUE_CAPACITY = 256
    const val MAX_FRAME_BYTES = 64L * 1024
    const val PING_PERIOD_SEC = 20
    const val TIMEOUT_SEC = 30
    const val SUMMARY_MIN_INTERVAL_MS = 2_000L
}

/** Конверт на проводе; `ts` у клиента не пишется. */
@Serializable
internal data class WsEnvelope(
    val v: Int,
    val type: String,
    val ts: Long? = null,
    val data: JsonElement = JsonObject(emptyMap()),
)

internal object WsServerMessageSerializer : KSerializer<WsServerMessage> {
    override val descriptor: SerialDescriptor = WsEnvelope.serializer().descriptor

    override fun serialize(encoder: Encoder, value: WsServerMessage) {
        val json = encoder.asJson().json
        val (type, data) = when (value) {
            is WsServerMessage.Hello -> WsTypes.HELLO to json.encodeToJsonElement(WsHelloDto.serializer(), value.data)
            is WsServerMessage.DeviceStatus ->
                WsTypes.DEVICE_STATUS to json.encodeToJsonElement(WsDeviceStatusDto.serializer(), value.data)
            is WsServerMessage.Telemetry ->
                WsTypes.TELEMETRY to json.encodeToJsonElement(WsTelemetryDto.serializer(), value.data)
            is WsServerMessage.Alert -> WsTypes.ALERT to json.encodeToJsonElement(AlertDto.serializer(), value.data)
            is WsServerMessage.Incident ->
                WsTypes.INCIDENT to json.encodeToJsonElement(IncidentDto.serializer(), value.data)
            is WsServerMessage.SummaryChanged ->
                WsTypes.SUMMARY_CHANGED to json.encodeToJsonElement(SummaryDto.serializer(), value.data)
            is WsServerMessage.Resync ->
                WsTypes.RESYNC to json.encodeToJsonElement(WsResyncDto.serializer(), value.data)
            is WsServerMessage.Unknown -> value.type to value.data
        }
        val v = (value as? WsServerMessage.Unknown)?.v ?: WS_PROTOCOL_VERSION
        encoder.encodeSerializableValue(WsEnvelope.serializer(), WsEnvelope(v, type, value.ts, data))
    }

    override fun deserialize(decoder: Decoder): WsServerMessage {
        val json = decoder.asJson().json
        val e = decoder.decodeSerializableValue(WsEnvelope.serializer())
        val ts = e.ts ?: 0
        if (e.v != WS_PROTOCOL_VERSION) return WsServerMessage.Unknown(e.v, e.type, ts, e.data)
        return when (e.type) {
            WsTypes.HELLO -> WsServerMessage.Hello(ts, json.decodeFromJsonElement(WsHelloDto.serializer(), e.data))
            WsTypes.DEVICE_STATUS ->
                WsServerMessage.DeviceStatus(ts, json.decodeFromJsonElement(WsDeviceStatusDto.serializer(), e.data))
            WsTypes.TELEMETRY ->
                WsServerMessage.Telemetry(ts, json.decodeFromJsonElement(WsTelemetryDto.serializer(), e.data))
            WsTypes.ALERT -> WsServerMessage.Alert(ts, json.decodeFromJsonElement(AlertDto.serializer(), e.data))
            WsTypes.INCIDENT ->
                WsServerMessage.Incident(ts, json.decodeFromJsonElement(IncidentDto.serializer(), e.data))
            WsTypes.SUMMARY_CHANGED ->
                WsServerMessage.SummaryChanged(ts, json.decodeFromJsonElement(SummaryDto.serializer(), e.data))
            WsTypes.RESYNC -> WsServerMessage.Resync(ts, json.decodeFromJsonElement(WsResyncDto.serializer(), e.data))
            else -> WsServerMessage.Unknown(e.v, e.type, ts, e.data)
        }
    }
}

internal object WsClientMessageSerializer : KSerializer<WsClientMessage> {
    override val descriptor: SerialDescriptor = WsEnvelope.serializer().descriptor

    override fun serialize(encoder: Encoder, value: WsClientMessage) {
        val json = encoder.asJson().json
        val envelope = when (value) {
            is WsClientMessage.SubscribeDevice -> WsEnvelope(
                WS_PROTOCOL_VERSION, WsTypes.SUBSCRIBE_DEVICE,
                data = json.encodeToJsonElement(WsDeviceRefDto.serializer(), value.data),
            )
            is WsClientMessage.UnsubscribeDevice -> WsEnvelope(
                WS_PROTOCOL_VERSION, WsTypes.UNSUBSCRIBE_DEVICE,
                data = json.encodeToJsonElement(WsDeviceRefDto.serializer(), value.data),
            )
            is WsClientMessage.Unknown -> WsEnvelope(value.v, value.type, data = value.data)
        }
        encoder.encodeSerializableValue(WsEnvelope.serializer(), envelope)
    }

    override fun deserialize(decoder: Decoder): WsClientMessage {
        val json = decoder.asJson().json
        val e = decoder.decodeSerializableValue(WsEnvelope.serializer())
        if (e.v != WS_PROTOCOL_VERSION) return WsClientMessage.Unknown(e.v, e.type, e.data)
        return when (e.type) {
            WsTypes.SUBSCRIBE_DEVICE ->
                WsClientMessage.SubscribeDevice(json.decodeFromJsonElement(WsDeviceRefDto.serializer(), e.data))
            WsTypes.UNSUBSCRIBE_DEVICE ->
                WsClientMessage.UnsubscribeDevice(json.decodeFromJsonElement(WsDeviceRefDto.serializer(), e.data))
            else -> WsClientMessage.Unknown(e.v, e.type, e.data)
        }
    }
}

private fun Encoder.asJson(): JsonEncoder =
    this as? JsonEncoder ?: throw SerializationException("WebSocket messages are JSON only")

private fun Decoder.asJson(): JsonDecoder =
    this as? JsonDecoder ?: throw SerializationException("WebSocket messages are JSON only")
