package ru.restofleet.server.domain.polling.usecase

import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceAnnouncement
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.device.DeviceTransport
import ru.restofleet.server.domain.ingest.DeviceDataBatch
import ru.restofleet.server.domain.ingest.IngestResult
import ru.restofleet.server.domain.ingest.usecase.IngestBatch
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId

/**
 * `POST /poll/telemetry` (FR-BE-54): опрос отмечает присутствие (`online`, транспорт `poll`, `last_seen_at`; реш. 214),
 * данные пишутся тем же [IngestBatch], что и MQTT. Записи с чужим `deviceId` отбрасываются: [deviceId] —
 * аутентифицированное устройство ([AuthenticateDevice]).
 */
class PollTelemetry(
    private val devices: DeviceRepository,
    private val ingest: IngestBatch,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(deviceId: DeviceId, batch: DeviceDataBatch): IngestResult {
        tx.inTransaction { devices.markOnline(deviceId, clock.nowMillis(), DeviceTransport.POLL, DeviceAnnouncement()) }
        val own = batch.onlyFrom(setOf(deviceId))
        val result = ingest(own)
        return result.copy(dropped = result.dropped + batch.size - own.size)
    }
}
