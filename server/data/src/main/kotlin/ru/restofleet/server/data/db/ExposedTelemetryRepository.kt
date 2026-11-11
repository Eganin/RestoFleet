package ru.restofleet.server.data.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.TimeRange
import ru.restofleet.server.domain.ingest.DeviceEvent
import ru.restofleet.server.domain.ingest.ProbeRecord
import ru.restofleet.server.domain.ingest.ProbeReport
import ru.restofleet.server.domain.ingest.ProbeResult
import ru.restofleet.server.domain.ingest.TelemetryRepository
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.shared.kernel.DeviceId
import java.time.OffsetDateTime

/**
 * `telemetry`, `probe_results`, `events`: пакетная вставка; телеметрия и пробы — `ON CONFLICT DO NOTHING` по
 * первичному ключу (реш. 205), число записанных — по строкам `RETURNING`.
 */
internal class ExposedTelemetryRepository : TelemetryRepository {
    override suspend fun insertTelemetry(items: List<TelemetrySnapshot>): Int =
        TelemetryTable.batchInsert(items, ignore = true, shouldReturnGeneratedValues = true) { t ->
            this[TelemetryTable.deviceId] = t.deviceId.value
            this[TelemetryTable.ts] = t.ts.toTimestamp()
            this[TelemetryTable.seq] = t.seq
            this[TelemetryTable.batteryLevel] = t.batteryLevel?.toShort()
            this[TelemetryTable.charging] = t.charging
            this[TelemetryTable.tempC] = t.tempC
            this[TelemetryTable.thermal] = t.thermal
            this[TelemetryTable.rssi] = t.rssi
            this[TelemetryTable.freeMb] = t.freeMb
            this[TelemetryTable.availMb] = t.availMb
            this[TelemetryTable.fgApp] = t.foregroundApp
            this[TelemetryTable.payload] = t.payload
        }.size

    override suspend fun insertProbes(reports: List<ProbeReport>): Int {
        val rows = reports.flatMap { r -> r.results.map { ProbeRecord(r.deviceId, r.ts, it) } }
        return ProbeResultsTable.batchInsert(rows, ignore = true, shouldReturnGeneratedValues = true) { p ->
            this[ProbeResultsTable.deviceId] = p.deviceId.value
            this[ProbeResultsTable.ts] = p.ts.toTimestamp()
            this[ProbeResultsTable.target] = p.result.target
            this[ProbeResultsTable.ok] = p.result.ok
            this[ProbeResultsTable.status] = p.result.status
            this[ProbeResultsTable.error] = p.result.error
            this[ProbeResultsTable.totalMs] = p.result.totalMs
            this[ProbeResultsTable.dnsMs] = p.result.dnsMs
            this[ProbeResultsTable.connectMs] = p.result.connectMs
            this[ProbeResultsTable.tlsMs] = p.result.tlsMs
            this[ProbeResultsTable.ttfbMs] = p.result.ttfbMs
        }.size
    }

    override suspend fun insertEvents(events: List<DeviceEvent>): Int {
        EventsTable.batchInsert(events, shouldReturnGeneratedValues = false) { e ->
            this[EventsTable.deviceId] = e.deviceId.value
            this[EventsTable.ts] = e.ts.toTimestamp()
            this[EventsTable.type] = e.type
            this[EventsTable.payload] = e.payload
        }
        return events.size
    }

    override suspend fun latest(deviceId: DeviceId): TelemetrySnapshot? = TelemetryTable.selectAll()
        .where { TelemetryTable.deviceId eq deviceId.value }
        .orderBy(TelemetryTable.ts, SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.toSnapshot()

    override suspend fun telemetry(deviceId: DeviceId, range: TimeRange, page: PageRequest): List<TelemetrySnapshot> =
        TelemetryTable.selectAll()
            .where { (TelemetryTable.deviceId eq deviceId.value) and range.on(TelemetryTable.ts) }
            .orderBy(TelemetryTable.ts, SortOrder.DESC)
            .limit(page.limit)
            .offset(page.offset)
            .map { it.toSnapshot() }

    override suspend fun probes(
        deviceId: DeviceId,
        range: TimeRange,
        target: String?,
        page: PageRequest,
    ): List<ProbeRecord> = ProbeResultsTable.selectAll()
        .where {
            val base = (ProbeResultsTable.deviceId eq deviceId.value) and range.on(ProbeResultsTable.ts)
            if (target == null) base else base and (ProbeResultsTable.target eq target)
        }
        .orderBy(ProbeResultsTable.ts to SortOrder.DESC, ProbeResultsTable.target to SortOrder.ASC)
        .limit(page.limit)
        .offset(page.offset)
        .map { it.toProbe() }

    override suspend fun events(
        deviceId: DeviceId,
        range: TimeRange,
        type: String?,
        page: PageRequest,
    ): List<DeviceEvent> = EventsTable.selectAll()
        .where {
            val base = (EventsTable.deviceId eq deviceId.value) and range.on(EventsTable.ts)
            if (type == null) base else base and (EventsTable.type eq type)
        }
        .orderBy(EventsTable.ts to SortOrder.DESC, EventsTable.id to SortOrder.DESC)
        .limit(page.limit)
        .offset(page.offset)
        .map {
            DeviceEvent(
                deviceId = it.deviceIdOf(EventsTable.deviceId),
                ts = it[EventsTable.ts].toMillis(),
                type = it[EventsTable.type],
                payload = it[EventsTable.payload],
            )
        }

    private fun TimeRange.on(column: Column<OffsetDateTime>): Op<Boolean> {
        val lower: Op<Boolean> = from?.let { column greaterEq it.toTimestamp() } ?: Op.TRUE
        return to?.let { lower and (column less it.toTimestamp()) } ?: lower
    }

    private fun ResultRow.deviceIdOf(column: Column<String>): DeviceId =
        DeviceId.parse(this[column]) ?: error("invalid device id in database")

    private fun ResultRow.toSnapshot() = TelemetrySnapshot(
        deviceId = deviceIdOf(TelemetryTable.deviceId),
        ts = this[TelemetryTable.ts].toMillis(),
        seq = this[TelemetryTable.seq],
        batteryLevel = this[TelemetryTable.batteryLevel]?.toInt(),
        charging = this[TelemetryTable.charging],
        tempC = this[TelemetryTable.tempC],
        thermal = this[TelemetryTable.thermal],
        rssi = this[TelemetryTable.rssi],
        freeMb = this[TelemetryTable.freeMb],
        availMb = this[TelemetryTable.availMb],
        foregroundApp = this[TelemetryTable.fgApp],
        payload = this[TelemetryTable.payload],
    )

    private fun ResultRow.toProbe() = ProbeRecord(
        deviceId = deviceIdOf(ProbeResultsTable.deviceId),
        ts = this[ProbeResultsTable.ts].toMillis(),
        result = ProbeResult(
            target = this[ProbeResultsTable.target],
            ok = this[ProbeResultsTable.ok],
            status = this[ProbeResultsTable.status],
            error = this[ProbeResultsTable.error],
            totalMs = this[ProbeResultsTable.totalMs],
            dnsMs = this[ProbeResultsTable.dnsMs],
            connectMs = this[ProbeResultsTable.connectMs],
            tlsMs = this[ProbeResultsTable.tlsMs],
            ttfbMs = this[ProbeResultsTable.ttfbMs],
        ),
    )
}
