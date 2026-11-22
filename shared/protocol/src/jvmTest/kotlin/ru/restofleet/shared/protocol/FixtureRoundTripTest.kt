package ru.restofleet.shared.protocol

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import ru.restofleet.shared.protocol.dto.CommandRequestDto
import ru.restofleet.shared.protocol.dto.CommandResultDto
import ru.restofleet.shared.protocol.dto.CommandStatusDto
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.ProbeReportDto
import ru.restofleet.shared.protocol.dto.ProbeStateEventDto
import ru.restofleet.shared.protocol.dto.ShadowDesiredDto
import ru.restofleet.shared.protocol.dto.ShadowReportedDto
import ru.restofleet.shared.protocol.dto.StatusDto
import ru.restofleet.shared.protocol.dto.TelemetryDto
import ru.restofleet.shared.protocol.dto.ThresholdEventDto
import ru.restofleet.shared.protocol.rest.PollCommandsResponseDto
import ru.restofleet.shared.protocol.rest.PollTelemetryRequestDto
import ru.restofleet.shared.protocol.rest.ProvisionRequestDto
import ru.restofleet.shared.protocol.rest.ProvisionResponseDto
import ru.restofleet.shared.protocol.rest.ApiErrorDto
import ru.restofleet.shared.protocol.rest.console.AlertActionDto
import ru.restofleet.shared.protocol.rest.console.AlertDto
import ru.restofleet.shared.protocol.rest.console.CommandDto
import ru.restofleet.shared.protocol.rest.console.CreateRestaurantRequestDto
import ru.restofleet.shared.protocol.rest.console.DecommissionDto
import ru.restofleet.shared.protocol.rest.console.EventRecordDto
import ru.restofleet.shared.protocol.rest.console.IssueTokensBatchRequestDto
import ru.restofleet.shared.protocol.rest.console.ProbeRecordDto
import ru.restofleet.shared.protocol.rest.console.UpdateRestaurantRequestDto
import ru.restofleet.shared.protocol.rest.console.DeviceDetailsDto
import ru.restofleet.shared.protocol.rest.console.DeviceDto
import ru.restofleet.shared.protocol.rest.console.IncidentActionDto
import ru.restofleet.shared.protocol.rest.console.IncidentDetailsDto
import ru.restofleet.shared.protocol.rest.console.IncidentDto
import ru.restofleet.shared.protocol.rest.console.IssueTokenRequestDto
import ru.restofleet.shared.protocol.rest.console.IssuedTokenDto
import ru.restofleet.shared.protocol.rest.console.ItemsDto
import ru.restofleet.shared.protocol.rest.console.LoginRequestDto
import ru.restofleet.shared.protocol.rest.console.LogoutRequestDto
import ru.restofleet.shared.protocol.rest.console.RefreshRequestDto
import ru.restofleet.shared.protocol.rest.console.RestaurantDto
import ru.restofleet.shared.protocol.rest.console.SendCommandRequestDto
import ru.restofleet.shared.protocol.rest.console.ShadowDto
import ru.restofleet.shared.protocol.rest.console.SummaryDto
import ru.restofleet.shared.protocol.rest.console.TokenResponseDto
import ru.restofleet.shared.protocol.rest.console.ws.WsClientMessage
import ru.restofleet.shared.protocol.rest.console.ws.WsServerMessage
import ru.restofleet.shared.protocol.rest.console.ws.WsTypes
import java.io.File
import kotlin.test.assertFailsWith

class FixtureRoundTripTest {

    private val fixturesDir = File(requireNotNull(System.getProperty("fixturesDir")) { "fixturesDir is not set" })

    /** Файлу — сериализатор самого длинного подходящего префикса (`rest-poll-commands-response`, а не `-command`). */
    private val serializers: List<Pair<String, KSerializer<*>>> = listOf(
        "rest-provision-request" to ProvisionRequestDto.serializer(),
        "rest-provision-response" to ProvisionResponseDto.serializer(),
        "rest-poll-telemetry-request" to PollTelemetryRequestDto.serializer(),
        "rest-poll-commands-response" to PollCommandsResponseDto.serializer(),
        "rest-poll-command-result" to CommandResultDto.serializer(),
        "status-" to StatusDto.serializer(),
        "telemetry-" to TelemetryDto.serializer(),
        "probe-" to ProbeReportDto.serializer(),
        "event-" to EventDto.serializer(),
        "shadow-desired" to ShadowDesiredDto.serializer(),
        "shadow-reported" to ShadowReportedDto.serializer(),
        "cmd-req" to CommandRequestDto.serializer(),
        "cmd-res" to CommandResultDto.serializer(),
        // REST-контракт консоли (ПМ1, реш. 408) и WebSocket (реш. 418, 419)
        "rest-console-login-request" to LoginRequestDto.serializer(),
        "rest-console-refresh-request" to RefreshRequestDto.serializer(),
        "rest-console-logout-request" to LogoutRequestDto.serializer(),
        "rest-console-token-response" to TokenResponseDto.serializer(),
        "rest-console-error" to ApiErrorDto.serializer(),
        "rest-console-restaurant" to RestaurantDto.serializer(),
        "rest-console-device" to DeviceDto.serializer(),
        "rest-console-device-details" to DeviceDetailsDto.serializer(),
        "rest-console-shadow" to ShadowDto.serializer(),
        "rest-console-command" to CommandDto.serializer(),
        "rest-console-command-request" to SendCommandRequestDto.serializer(),
        "rest-console-issue-token-request" to IssueTokenRequestDto.serializer(),
        "rest-console-issued-token" to IssuedTokenDto.serializer(),
        "rest-console-alert" to AlertDto.serializer(),
        "rest-console-alert-action" to AlertActionDto.serializer(),
        "rest-console-alerts-page" to ItemsDto.serializer(AlertDto.serializer()),
        "rest-console-incident" to IncidentDto.serializer(),
        "rest-console-incident-action" to IncidentActionDto.serializer(),
        "rest-console-incident-details" to IncidentDetailsDto.serializer(),
        "rest-console-summary" to SummaryDto.serializer(),
        "rest-console-decommission" to DecommissionDto.serializer(),
        "rest-console-event-record" to EventRecordDto.serializer(),
        "rest-console-probe-record" to ProbeRecordDto.serializer(),
        "rest-console-create-restaurant-request" to CreateRestaurantRequestDto.serializer(),
        "rest-console-update-restaurant-request" to UpdateRestaurantRequestDto.serializer(),
        "rest-console-issue-tokens-batch-request" to IssueTokensBatchRequestDto.serializer(),
        "ws-" to WsServerMessage.serializer(),
        "ws-client-" to WsClientMessage.serializer(),
    )

    private val fixtures: List<File>
        get() = fixturesDir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    private fun serializerFor(file: File): KSerializer<*>? =
        serializers.filter { file.name.startsWith(it.first) }.maxByOrNull { it.first.length }?.second

    @Test
    fun fixturesArePresent() {
        assertThat(fixtures.size).isAtLeast(MIN_FIXTURES)
    }

    @Test
    fun everyEventTypeHasFixture() {
        // Подклассы sealed-иерархии: элемент 1 дескриптора — "value" с serial name каждого подкласса
        val declared = EventDto.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()
        val covered = typesOf("event-", "type")
        assertThat(covered).containsExactlyElementsIn(declared)
    }

    @Test
    fun everyCommandStatusHasFixture() {
        val declared = CommandStatusDto.serializer().descriptor.elementNames.toSet()
        assertThat(typesOf("cmd-res-", "status")).containsExactlyElementsIn(declared)
    }

    @Test
    fun everyCommandTypeHasFixture() {
        assertThat(typesOf("cmd-req-", "type")).containsExactlyElementsIn(CommandTypes.ALL)
    }

    @Test
    fun everyWebSocketTypeHasFixture() {
        assertThat(typesOf("ws-", "type") - WsTypes.CLIENT).containsExactlyElementsIn(WsTypes.SERVER)
        assertThat(typesOf("ws-client-", "type")).containsExactlyElementsIn(WsTypes.CLIENT)
    }

    @Test
    fun webSocketFixturesDecodeToKnownMessages() {
        // Ни одна фикстура не должна молча превратиться в Unknown (опечатка в type или v)
        fixtures.filter { it.name.startsWith("ws-") && !it.name.startsWith("ws-client-") }.forEach { file ->
            val message = RfJson.decodeFromString(WsServerMessage.serializer(), file.readText())
            assertWithMessage(file.name).that(message).isNotInstanceOf(WsServerMessage.Unknown::class.java)
        }
        fixtures.filter { it.name.startsWith("ws-client-") }.forEach { file ->
            val message = RfJson.decodeFromString(WsClientMessage.serializer(), file.readText())
            assertWithMessage(file.name).that(message).isNotInstanceOf(WsClientMessage.Unknown::class.java)
        }
    }

    @Test
    fun everyConsoleFixtureFieldReachesItsProperty() {
        // Каждое поле фикстуры консоли декодируется в свойство DTO с тем же именем и значением (ПМ1, реш. 442)
        fixtures.filter { it.name.startsWith("rest-console-") || it.name.startsWith("ws-") }.forEach { file ->
            @Suppress("UNCHECKED_CAST")
            val serializer = requireNotNull(serializerFor(file)) as KSerializer<Any>
            val decoded = RfJson.decodeFromString(serializer, file.readText())
            assertReachesProperties(file.name, RfJson.parseToJsonElement(file.readText()), decoded)
        }
    }

    private fun assertReachesProperties(path: String, json: JsonElement, value: Any?) {
        when {
            value is JsonElement -> assertWithMessage(path).that(value).isEqualTo(json)
            json is JsonObject && value is Map<*, *> ->
                json.forEach { (key, child) -> assertReachesProperties("$path.$key", child, value[key]) }
            json is JsonObject -> json.forEach { (key, child) ->
                val getter = value?.javaClass?.methods?.firstOrNull { m ->
                    m.parameterCount == 0 && m.name == "get" + key.replaceFirstChar { it.uppercase() }
                }
                if (getter == null) {
                    // Служебные поля конверта WS (`v`, `type`) у типизированных сообщений свойств не имеют
                    assertWithMessage("$path.$key").that(key).isIn(ENVELOPE_KEYS)
                } else {
                    assertReachesProperties("$path.$key", child, getter.invoke(value))
                }
            }
            json is JsonArray && value is List<*> -> {
                assertWithMessage(path).that(value).hasSize(json.size)
                json.forEachIndexed { i, child -> assertReachesProperties("$path[$i]", child, value[i]) }
            }
            json is JsonNull -> assertWithMessage(path).that(value).isNull()
            else -> assertWithMessage(path).that(value.toString()).isEqualTo((json as JsonPrimitive).content)
        }
    }

    @Test
    fun everyStatusReasonHasFixture() {
        assertThat(typesOf("status-offline", "reason")).containsExactly("lwt", "stop")
    }

    @Test
    fun commandResultValuesAreStrings() {
        // ТЗ 7.2: `result` — объект со строковыми значениями (реш. 123); DTO это не навязывает, фикстуры — да
        fixtures.filter { it.name.startsWith("cmd-res-") || it.name.startsWith("rest-poll-command-result") }
            .mapNotNull { (RfJson.parseToJsonElement(it.readText()) as JsonObject)["result"] as? JsonObject }
            .flatMap { it.values }
            .forEach { assertThat((it as JsonPrimitive).isString).isTrue() }
    }

    @Test
    fun everyFixtureHasSerializer() {
        fixtures.forEach { assertWithMessage(it.name).that(serializerFor(it)).isNotNull() }
    }

    private fun typesOf(prefix: String, key: String): Set<String> =
        fixtures.filter { it.name.startsWith(prefix) }
            .mapNotNull { ((RfJson.parseToJsonElement(it.readText()) as JsonObject)[key] as? JsonPrimitive)?.content }
            .toSet()

    @Test
    fun everyFixtureRoundTrips() {
        fixtures.forEach { file ->
            @Suppress("UNCHECKED_CAST")
            val serializer = requireNotNull(serializerFor(file)) as KSerializer<Any>
            val source = file.readText()
            val decoded = RfJson.decodeFromString(serializer, source)
            val encoded = RfJson.encodeToString(serializer, decoded)
            assertWithMessage(file.name)
                .that(RfJson.parseToJsonElement(encoded))
                .isEqualTo(RfJson.parseToJsonElement(source))
        }
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val withFuture = File(fixturesDir, "telemetry-kitchen.json").readText()
            .replaceFirst("{", """{ "futureField": { "x": 1 }, """)
        val dto = RfJson.decodeFromString(TelemetryDto.serializer(), withFuture)
        assertThat(dto.seq).isEqualTo(18234)
    }

    @Test
    fun unknownProbeErrorCodeDecodesAsString() {
        val json = """{ "ts": 1, "results": [ { "id": "api", "ok": false, "error": "quic_fail", "totalMs": 10 } ] }"""
        val dto = RfJson.decodeFromString(ProbeReportDto.serializer(), json)
        assertThat(dto.results.single().error).isEqualTo("quic_fail")
    }

    @Test
    fun unknownEventTypeFails() {
        // Фиксирует текущее поведение черновика: неизвестный тип события не декодируется (обработка — в К на бэкенде)
        assertFailsWith<SerializationException> {
            RfJson.decodeFromString(EventDto.serializer(), """{ "type": "teleport", "ts": 1 }""")
        }
    }

    @Test
    fun eventsDecodeToTypedSubclasses() {
        val source = File(fixturesDir, "event-probe-state.json").readText()
        val event = RfJson.decodeFromString(EventDto.serializer(), source)
        val expected = ProbeStateEventDto(
            ts = 1793865700000, target = "api", state = "down", error = "timeout", failures = 3,
        )
        assertThat(event).isEqualTo(expected)
    }

    @Test
    fun thresholdEventDecodesTyped() {
        val event = RfJson.decodeFromString(
            EventDto.serializer(), File(fixturesDir, "event-threshold-enter.json").readText(),
        )
        assertThat(event).isEqualTo(
            ThresholdEventDto(ts = 1793865750000, metric = "battery_low", state = "enter", value = 19.0, limit = 20.0),
        )
    }

    @Test
    fun expectedStatusIsOptional() {
        val desired = RfJson.decodeFromString(
            ShadowDesiredDto.serializer(), File(fixturesDir, "shadow-desired-expected-status.json").readText(),
        )
        assertThat(desired.probes.single().expectedStatus).isEqualTo(204)
        val plain = RfJson.decodeFromString(
            ShadowDesiredDto.serializer(), File(fixturesDir, "shadow-desired.json").readText(),
        )
        assertThat(plain.probes.map { it.expectedStatus }).containsExactly(null, null, null)
    }

    @Test
    fun commandDefaultsAreApplied() {
        val dto = RfJson.decodeFromString(CommandRequestDto.serializer(), """{ "cmdId": "c-1", "type": "ping" }""")
        assertThat(dto.params).isEqualTo(JsonObject(emptyMap()))
        assertThat(dto.expiresAt).isNull()
    }

    private companion object {
        const val MIN_FIXTURES = 39
        val ENVELOPE_KEYS = setOf("v", "type")
    }

    @Test
    fun commandStatusUsesLowercaseWireNames() {
        val dto = CommandResultDto(cmdId = "c-1", status = CommandStatusDto.SUCCEEDED, ts = 5)
        val json = RfJson.parseToJsonElement(RfJson.encodeToString(CommandResultDto.serializer(), dto)) as JsonObject
        assertThat(json["status"]).isEqualTo(JsonPrimitive("succeeded"))
    }
}
