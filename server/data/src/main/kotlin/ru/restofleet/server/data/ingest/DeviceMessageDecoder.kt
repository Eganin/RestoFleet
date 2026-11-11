package ru.restofleet.server.data.ingest

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import ru.restofleet.server.data.mapper.ShadowJson
import ru.restofleet.server.domain.command.CommandReport
import ru.restofleet.server.domain.command.ReportedCommandStatus
import ru.restofleet.server.domain.device.DeviceAnnouncement
import ru.restofleet.server.domain.device.DeviceInfo
import ru.restofleet.server.domain.ingest.DeviceEvent
import ru.restofleet.server.domain.ingest.ProbeReport
import ru.restofleet.server.domain.ingest.ProbeResult
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.server.domain.presence.PresenceState
import ru.restofleet.server.domain.presence.StatusReport
import ru.restofleet.server.domain.shadow.ReportedShadow
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.probes.ProbeErrorClass
import ru.restofleet.shared.protocol.DeviceChannel
import ru.restofleet.shared.protocol.DeviceTopic
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.Topics
import ru.restofleet.shared.protocol.dto.CommandResultDto
import ru.restofleet.shared.protocol.dto.CommandStatusDto
import ru.restofleet.shared.protocol.dto.DebugSeqEventDto
import ru.restofleet.shared.protocol.dto.DeviceStateDto
import ru.restofleet.shared.protocol.dto.EventDto
import ru.restofleet.shared.protocol.dto.ProbeReportDto
import ru.restofleet.shared.protocol.dto.StatusDto
import ru.restofleet.shared.protocol.dto.TelemetryDto
import ru.restofleet.shared.shadow.ShadowKeys

/** Разобранное входящее сообщение устройства (MQTT-DTO `shared:protocol` → модель `server:domain`). */
sealed interface Decoded {
    data class Status(val report: StatusReport) : Decoded
    data class Telemetry(val snapshot: TelemetrySnapshot) : Decoded
    data class Probe(val report: ProbeReport) : Decoded
    data class Event(val event: DeviceEvent) : Decoded
    data class Reported(val report: ReportedShadow) : Decoded
    data class CommandResult(val report: CommandReport) : Decoded

    /** Сообщение не для бэкенда (служебное `debug_seq`, канал к устройству) — подтверждается и не пишется. */
    data class Ignored(val reason: String) : Decoded

    /** Не разобрано (чужой топик, неверный JSON, неизвестный тип) — подтверждается, считается, в лог без payload. */
    data class Undecodable(val reason: String) : Decoded
}

/**
 * Маппер MQTT-сообщений устройств в доменные модели (арх. разд. 3.1: DTO — слой data). Устройство определяется по
 * топику (DynSec не даёт писать в чужой), длины строк обрезаются под столбцы БД, класс ошибки пробы нормализуется
 * (`ProbeErrorClass.fromCode`: неизвестный — `other`).
 *
 * Всё, что PostgreSQL отверг бы детерминированно, отсекается здесь как [Decoded.Undecodable] (реш. 241), иначе одно
 * сообщение заставляло бы разбирать каждую пачку по одному: символ NUL (`text`/`jsonb` его не принимают), время вне
 * 1970–2100 (`timestamptz`), заряд вне 0…100 (`smallint`) — последний просто не пишется.
 */
class DeviceMessageDecoder(private val maxPayloadBytes: Int) {
    fun decode(topic: String, payload: ByteArray): Decoded {
        val route = Topics.parse(topic)
        return when {
            route == null -> Decoded.Undecodable("unknown_topic")
            payload.size > maxPayloadBytes -> Decoded.Undecodable("too_large")
            payload.isEmpty() -> Decoded.Ignored("empty")
            else -> parse(route, payload.decodeToString())
        }
    }

    private fun parse(route: DeviceTopic, text: String): Decoded = when {
        text.contains(NUL) || text.contains(NUL_ESCAPE) -> Decoded.Undecodable("nul_char")
        else -> try {
            decode(route.deviceId, route.channel, text)
        } catch (e: IllegalArgumentException) {
            // SerializationException — наследник IllegalArgumentException; payload в причину не попадает
            Decoded.Undecodable("bad_json:${route.channel.suffix}:${e.javaClass.simpleName}")
        }
    }

    private fun decode(id: DeviceId, channel: DeviceChannel, text: String): Decoded {
        val decoded = when (channel) {
            DeviceChannel.STATUS -> Decoded.Status(status(id, RfJson.decodeFromString(StatusDto.serializer(), text)))
            DeviceChannel.TELEMETRY ->
                Decoded.Telemetry(telemetry(id, RfJson.decodeFromString(TelemetryDto.serializer(), text), text))
            DeviceChannel.PROBE -> Decoded.Probe(probe(id, RfJson.decodeFromString(ProbeReportDto.serializer(), text)))
            DeviceChannel.EVENTS -> event(id, text)
            DeviceChannel.SHADOW_REPORTED -> reported(id, text)
            DeviceChannel.CMD_RES ->
                Decoded.CommandResult(result(id, RfJson.decodeFromString(CommandResultDto.serializer(), text)))
            DeviceChannel.SHADOW_DESIRED, DeviceChannel.CMD_REQ -> Decoded.Ignored("to_device")
        }
        val timesValid = decoded.storedTimes().all { it in VALID_TS }
        return if (timesValid) decoded else Decoded.Undecodable("bad_ts:${channel.suffix}")
    }

    /** Время для столбцов `timestamptz` (время `status` и `cmd/res` не хранится — берётся время бэкенда). */
    private fun Decoded.storedTimes(): List<Long> = when (this) {
        is Decoded.Telemetry -> listOf(snapshot.ts)
        is Decoded.Probe -> listOf(report.ts)
        is Decoded.Event -> listOf(event.ts)
        is Decoded.Reported -> listOf(report.appliedAt)
        else -> emptyList()
    }

    private fun status(id: DeviceId, dto: StatusDto) = StatusReport(
        deviceId = id,
        state = if (dto.state == DeviceStateDto.ONLINE) PresenceState.ONLINE else PresenceState.OFFLINE,
        ts = dto.ts,
        connId = dto.connId.take(MAX_ID),
        reason = dto.reason?.take(MAX_REASON),
        announcement = DeviceAnnouncement(
            agentVersion = dto.agentVersion,
            mode = dto.mode,
            info = dto.device?.let { DeviceInfo(it.model, it.manufacturer, it.osVersion, it.apiLevel) },
        ),
    )

    private fun telemetry(id: DeviceId, dto: TelemetryDto, raw: String) = TelemetrySnapshot(
        deviceId = id,
        ts = dto.ts,
        seq = dto.seq,
        batteryLevel = dto.battery?.level?.takeIf { it in BATTERY_LEVEL },
        charging = dto.battery?.charging,
        tempC = dto.battery?.tempC,
        thermal = dto.thermal?.status?.take(MAX_THERMAL),
        rssi = dto.net?.rssi,
        freeMb = dto.storage?.freeMb,
        availMb = dto.memory?.availMb,
        foregroundApp = dto.foregroundApp?.take(MAX_PACKAGE),
        payload = raw,
    )

    private fun probe(id: DeviceId, dto: ProbeReportDto) = ProbeReport(
        deviceId = id,
        ts = dto.ts,
        results = dto.results.distinctBy { it.id }.map {
            ProbeResult(
                target = it.id.take(MAX_ID),
                ok = it.ok,
                status = it.status,
                error = ProbeErrorClass.fromCode(it.error)?.code,
                totalMs = it.totalMs,
                dnsMs = it.dnsMs,
                connectMs = it.connectMs,
                tlsMs = it.tlsMs,
                ttfbMs = it.ttfbMs,
            )
        },
    )

    /** Неизвестный `type` не разбирается ([kotlinx.serialization.SerializationException]) — нечитаемое (A14). */
    private fun event(id: DeviceId, raw: String): Decoded {
        val dto = RfJson.decodeFromString(EventDto.serializer(), raw)
        val type = RfJson.parseToJsonElement(raw).jsonObject.getValue(TYPE_KEY).jsonPrimitive.content
        return if (dto is DebugSeqEventDto) {
            Decoded.Ignored("debug_seq")
        } else {
            Decoded.Event(DeviceEvent(id, dto.ts, type.take(MAX_TYPE), raw))
        }
    }

    /** `reported` разбирается обобщённо (документ тени — дерево), метаданные отделяются от документа. */
    private fun reported(id: DeviceId, text: String): Decoded {
        val json = RfJson.parseToJsonElement(text).jsonObject
        val applied = (json[ShadowKeys.APPLIED_VERSION] as? JsonPrimitive)?.longOrNull
        val appliedAt = (json[ShadowKeys.APPLIED_AT] as? JsonPrimitive)?.longOrNull
        val errors = (json[ShadowKeys.ERRORS] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        return if (applied == null || appliedAt == null) {
            Decoded.Undecodable("bad_json:shadow/reported:no_applied")
        } else {
            val document = ShadowJson.fromJson(JsonObject(json - ShadowKeys.METADATA))
            Decoded.Reported(ReportedShadow(id, document, applied, appliedAt, errors))
        }
    }

    private fun result(id: DeviceId, dto: CommandResultDto) = CommandReport(
        deviceId = id,
        cmdId = dto.cmdId,
        status = when (dto.status) {
            CommandStatusDto.RECEIVED -> ReportedCommandStatus.RECEIVED
            CommandStatusDto.SUCCEEDED -> ReportedCommandStatus.SUCCEEDED
            CommandStatusDto.FAILED -> ReportedCommandStatus.FAILED
            CommandStatusDto.UNSUPPORTED -> ReportedCommandStatus.UNSUPPORTED
        },
        ts = dto.ts,
        result = dto.result?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() },
        reason = dto.reason,
    )

    private companion object {
        const val MAX_ID = 64
        const val MAX_REASON = 32
        const val MAX_THERMAL = 16
        const val MAX_PACKAGE = 255
        const val MAX_TYPE = 32
        const val TYPE_KEY = "type"
        const val NUL = '\u0000'
        const val NUL_ESCAPE = "\\u0000"
        val BATTERY_LEVEL = 0..100

        /** 1970-01-01 … 2100-01-01 UTC, epoch ms. */
        val VALID_TS = 0L..4_102_444_800_000L
    }
}
