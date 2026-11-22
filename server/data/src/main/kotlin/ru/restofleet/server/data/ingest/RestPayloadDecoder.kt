package ru.restofleet.server.data.ingest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import ru.restofleet.server.domain.command.CommandReport
import ru.restofleet.server.domain.ingest.DeviceDataBatch
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.DeviceChannel
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.Topics
import ru.restofleet.shared.protocol.rest.PollTelemetryRequestDto

/**
 * Тела REST-опроса устройства (FR-BE-54, реш. 222) → модель домена **теми же правилами**, что MQTT-ingest (реш. 241,
 * 246): каждая часть `PollTelemetryRequestDto` (`telemetry`, `probe`, каждое событие) разбирается
 * [DeviceMessageDecoder] как сообщение соответствующего топика аутентифицированного устройства. Поэтому чужой
 * `deviceId` в теле невозможен в принципе (его там нет), а NUL, время вне 1970–2100 и неизвестный тип события
 * отклоняют весь запрос (A14). Причина отказа — машиночитаемая строка без payload.
 */
class RestPayloadDecoder(private val decoder: DeviceMessageDecoder) {

    fun telemetryBatch(deviceId: DeviceId, body: String): Outcome<DeviceDataBatch, String> {
        val json = parseObject(body) ?: return Outcome.Failure(BAD_JSON)
        return shapeError(json)?.let { Outcome.Failure(it) } ?: decodeParts(deviceId, json)
    }

    /** Структура тела по DTO протокола (обязательная `telemetry`, известные типы событий); `null` — в порядке. */
    private fun shapeError(json: JsonObject): String? = try {
        RfJson.decodeFromJsonElement(PollTelemetryRequestDto.serializer(), json)
        null
    } catch (e: IllegalArgumentException) {
        "$BAD_JSON:poll/telemetry:${e.javaClass.simpleName}"
    }

    private fun decodeParts(deviceId: DeviceId, json: JsonObject): Outcome<DeviceDataBatch, String> {
        val parts = buildList {
            add(DeviceChannel.TELEMETRY to json.getValue(TELEMETRY))
            json[PROBE]?.takeIf { it !is JsonNull }?.let { add(DeviceChannel.PROBE to it) }
            (json[EVENTS] as? JsonArray).orEmpty().forEach { add(DeviceChannel.EVENTS to it) }
        }
        val decoded = parts.map { (channel, part) -> decodePart(deviceId, channel, part) }
        val rejected = decoded.firstNotNullOfOrNull { (it as? Decoded.Undecodable)?.reason }
        return if (rejected != null) {
            Outcome.Failure(rejected)
        } else {
            Outcome.Success(
                DeviceDataBatch(
                    telemetry = decoded.filterIsInstance<Decoded.Telemetry>().map { it.snapshot },
                    probes = decoded.filterIsInstance<Decoded.Probe>().map { it.report },
                    events = decoded.filterIsInstance<Decoded.Event>().map { it.event },
                ),
            )
        }
    }

    fun commandReport(deviceId: DeviceId, body: String): Outcome<CommandReport, String> =
        when (val decoded = decoder.decode(Topics.of(deviceId, DeviceChannel.CMD_RES), body.encodeToByteArray())) {
            is Decoded.CommandResult -> Outcome.Success(decoded.report)
            is Decoded.Undecodable -> Outcome.Failure(decoded.reason)
            else -> Outcome.Failure("$BAD_JSON:cmd/res:empty")
        }

    private fun decodePart(deviceId: DeviceId, channel: DeviceChannel, part: JsonElement): Decoded {
        val text = RfJson.encodeToString(JsonElement.serializer(), part)
        return decoder.decode(Topics.of(deviceId, channel), text.encodeToByteArray())
    }

    private fun parseObject(body: String): JsonObject? = try {
        RfJson.parseToJsonElement(body) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val BAD_JSON = "bad_json"
        const val TELEMETRY = "telemetry"
        const val PROBE = "probe"
        const val EVENTS = "events"
    }
}
