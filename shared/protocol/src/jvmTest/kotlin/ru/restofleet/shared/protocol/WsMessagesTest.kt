package ru.restofleet.shared.protocol

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import ru.restofleet.shared.protocol.rest.console.ws.WsClientMessage
import ru.restofleet.shared.protocol.rest.console.ws.WsCloseCodes
import ru.restofleet.shared.protocol.rest.console.ws.WsDeviceRefDto
import ru.restofleet.shared.protocol.rest.console.ws.WsLimits
import ru.restofleet.shared.protocol.rest.console.ws.WsResyncDto
import ru.restofleet.shared.protocol.rest.console.ws.WsServerMessage
import ru.restofleet.shared.protocol.rest.console.ws.WsTypes
import ru.restofleet.shared.protocol.rest.console.ws.WS_PROTOCOL_VERSION
import kotlin.test.assertFailsWith

/** Конверт WebSocket `{v, type, ts, data}` (реш. 418, 419): типизированные сообщения и совместимость вперёд. */
class WsMessagesTest {

    private fun server(text: String) = RfJson.decodeFromString(WsServerMessage.serializer(), text)
    private fun client(text: String) = RfJson.decodeFromString(WsClientMessage.serializer(), text)

    @Test
    fun serverMessageIsEncodedAsEnvelope() {
        val message = WsServerMessage.Resync(ts = 5, data = WsResyncDto(reason = WsResyncDto.EVENTS_DROPPED))
        val json = RfJson.encodeToJsonElement(WsServerMessage.serializer(), message).jsonObject

        assertThat(json.keys).containsExactly("v", "type", "ts", "data")
        assertThat(json["v"]).isEqualTo(JsonPrimitive(WS_PROTOCOL_VERSION))
        assertThat(json["type"]).isEqualTo(JsonPrimitive(WsTypes.RESYNC))
        assertThat(json["data"]).isEqualTo(JsonObject(mapOf("reason" to JsonPrimitive("events_dropped"))))
        assertThat(server(RfJson.encodeToString(WsServerMessage.serializer(), message))).isEqualTo(message)
    }

    @Test
    fun clientMessageHasNoTimestamp() {
        val message = WsClientMessage.SubscribeDevice(WsDeviceRefDto(deviceId = "d-1"))
        val text = RfJson.encodeToString(WsClientMessage.serializer(), message)

        assertThat(text).isEqualTo("""{"v":1,"type":"subscribe_device","data":{"deviceId":"d-1"}}""")
        assertThat(client(text)).isEqualTo(message)
        val unsubscribe = WsClientMessage.UnsubscribeDevice(WsDeviceRefDto(deviceId = "d-1"))
        assertThat(client(RfJson.encodeToString(WsClientMessage.serializer(), unsubscribe))).isEqualTo(unsubscribe)
    }

    @Test
    fun unknownServerTypeIsKeptAsUnknownAndRoundTrips() {
        val text = """{"v":1,"type":"availability","ts":7,"data":{"x":1}}"""
        val message = server(text)

        val data = JsonObject(mapOf("x" to JsonPrimitive(1)))
        assertThat(message).isEqualTo(WsServerMessage.Unknown(v = 1, type = "availability", ts = 7, data = data))
        assertThat(RfJson.parseToJsonElement(RfJson.encodeToString(WsServerMessage.serializer(), message)))
            .isEqualTo(RfJson.parseToJsonElement(text))
    }

    @Test
    fun otherProtocolVersionIsUnknownEvenForKnownType() {
        val message = server("""{"v":2,"type":"resync","ts":7,"data":{"reason":"events_dropped"}}""")
        assertThat(message).isInstanceOf(WsServerMessage.Unknown::class.java)
        assertThat((message as WsServerMessage.Unknown).v).isEqualTo(2)
    }

    @Test
    fun missingDataAndTimestampDefault() {
        val message = server("""{"v":1,"type":"future"}""")
        val empty = JsonObject(emptyMap())
        assertThat(message).isEqualTo(WsServerMessage.Unknown(v = 1, type = "future", ts = 0, data = empty))
    }

    @Test
    fun unknownClientTypeIsUnknown() {
        val message = client("""{"v":1,"type":"subscribe_restaurant","data":{"restaurantId":"r"}}""")
        assertThat(message).isInstanceOf(WsClientMessage.Unknown::class.java)
        assertThat((message as WsClientMessage.Unknown).type).isEqualTo("subscribe_restaurant")
        val reencoded = RfJson.encodeToString(WsClientMessage.serializer(), message)
        assertThat(client(reencoded)).isEqualTo(message)
        assertThat(client("""{"v":3,"type":"subscribe_device","data":{"deviceId":"d"}}"""))
            .isInstanceOf(WsClientMessage.Unknown::class.java)
    }

    @Test
    fun knownTypeWithMalformedDataFails() {
        assertFailsWith<SerializationException> { server("""{"v":1,"type":"resync","ts":1,"data":{}}""") }
        assertFailsWith<SerializationException> { client("""{"v":1,"type":"subscribe_device","data":{}}""") }
    }

    @Test
    fun envelopeWithoutTypeFails() {
        assertFailsWith<SerializationException> { server("""{"v":1,"ts":1,"data":{}}""") }
    }

    @Test
    fun closeCodesAndLimitsMatchRulings() {
        assertThat(WsCloseCodes.TOKEN_EXPIRED).isEqualTo(4401)
        assertThat(WsCloseCodes.POLICY_VIOLATION).isEqualTo(1008)
        assertThat(WsCloseCodes.TRY_AGAIN_LATER).isEqualTo(1013)
        assertThat(WsCloseCodes.GOING_AWAY).isEqualTo(1001)
        assertThat(WsLimits.MAX_CONNECTIONS_PER_USER).isEqualTo(4)
        assertThat(WsLimits.MAX_SUBSCRIPTIONS).isEqualTo(5)
        assertThat(WsLimits.QUEUE_CAPACITY).isEqualTo(256)
        assertThat(WsLimits.MAX_FRAME_BYTES).isEqualTo(64L * 1024)
        assertThat(WsLimits.PING_PERIOD_SEC).isEqualTo(20)
        assertThat(WsLimits.TIMEOUT_SEC).isEqualTo(30)
        assertThat(WsLimits.SUMMARY_MIN_INTERVAL_MS).isEqualTo(2_000L)
        assertThat(WsTypes.SERVER intersect WsTypes.CLIENT).isEmpty()
    }
}
