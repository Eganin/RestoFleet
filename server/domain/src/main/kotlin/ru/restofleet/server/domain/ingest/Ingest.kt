package ru.restofleet.server.domain.ingest

import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.TimeRange
import ru.restofleet.shared.kernel.DeviceId

/**
 * Снимок телеметрии (FR-BE-11; столбцы `1-coursework.md` 4.4). [payload] — исходное сообщение JSON-текстом (домен
 * его не разбирает, хранится в `jsonb` для полей, не вынесенных в столбцы). Ключ уникальности — `(deviceId, ts)`
 * (реш. 205).
 */
data class TelemetrySnapshot(
    val deviceId: DeviceId,
    val ts: Long,
    val seq: Long,
    val batteryLevel: Int?,
    val charging: Boolean?,
    val tempC: Double?,
    val thermal: String?,
    val rssi: Int?,
    val freeMb: Long?,
    val availMb: Long?,
    val foregroundApp: String?,
    val payload: String,
)

/** Результат одной цели проверки (FR-AG-30…33). [error] — код класса ошибки (`ProbeErrorClass.code`). */
data class ProbeResult(
    val target: String,
    val ok: Boolean,
    val status: Int?,
    val error: String?,
    val totalMs: Long,
    val dnsMs: Long?,
    val connectMs: Long?,
    val tlsMs: Long?,
    val ttfbMs: Long?,
)

/** Сообщение `probe`: результаты всех целей одного прогона. */
data class ProbeReport(val deviceId: DeviceId, val ts: Long, val results: List<ProbeResult>)

/** Строка истории проверок. */
data class ProbeRecord(val deviceId: DeviceId, val ts: Long, val result: ProbeResult)

/** Событие устройства (`events`); [payload] — сообщение JSON-текстом. Идентификатора нет, дубль допустим (реш. 207). */
data class DeviceEvent(val deviceId: DeviceId, val ts: Long, val type: String, val payload: String)

/** Пачка данных устройств для одной транзакции ingest (MQTT: до 500 сообщений; REST: один опрос). */
data class DeviceDataBatch(
    val telemetry: List<TelemetrySnapshot> = emptyList(),
    val probes: List<ProbeReport> = emptyList(),
    val events: List<DeviceEvent> = emptyList(),
) {
    val size: Int get() = telemetry.size + probes.size + events.size

    fun deviceIds(): Set<DeviceId> =
        (telemetry.map { it.deviceId } + probes.map { it.deviceId } + events.map { it.deviceId }).toSet()

    fun onlyFrom(ids: Set<DeviceId>): DeviceDataBatch = DeviceDataBatch(
        telemetry.filter { it.deviceId in ids },
        probes.filter { it.deviceId in ids },
        events.filter { it.deviceId in ids },
    )
}

/**
 * Итог пачки: записано (дубли телеметрии и проб по ключу уникальности не считаются) и отброшено — сообщения
 * неизвестных и выведенных из эксплуатации устройств.
 */
data class IngestResult(val telemetry: Int, val probeResults: Int, val events: Int, val dropped: Int)

/** Итог обработки одиночного сообщения (`status`, `shadow/reported`, `cmd/res`). */
enum class IngestOutcome {
    APPLIED,

    /** Устаревшее или повторное: чужой `connId`, старая `appliedVersion`, повтор статуса команды. */
    IGNORED_STALE,

    DROPPED_UNKNOWN_DEVICE,
}

/** Порт временных рядов. Запись — пакетная и идемпотентная для телеметрии и проб; чтение — от новых к старым. */
interface TelemetryRepository {
    /** `ON CONFLICT (device_id, ts) DO NOTHING` (реш. 205); возвращает число записанных. */
    suspend fun insertTelemetry(items: List<TelemetrySnapshot>): Int

    /** По строке на цель; `ON CONFLICT (device_id, ts, target) DO NOTHING`. */
    suspend fun insertProbes(reports: List<ProbeReport>): Int

    suspend fun insertEvents(events: List<DeviceEvent>): Int

    suspend fun latest(deviceId: DeviceId): TelemetrySnapshot?

    suspend fun telemetry(deviceId: DeviceId, range: TimeRange, page: PageRequest): List<TelemetrySnapshot>

    suspend fun probes(deviceId: DeviceId, range: TimeRange, target: String?, page: PageRequest): List<ProbeRecord>

    suspend fun events(deviceId: DeviceId, range: TimeRange, type: String?, page: PageRequest): List<DeviceEvent>
}
