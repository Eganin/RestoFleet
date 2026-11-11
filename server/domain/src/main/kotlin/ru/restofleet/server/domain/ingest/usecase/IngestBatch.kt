package ru.restofleet.server.domain.ingest.usecase

import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.ingest.DeviceDataBatch
import ru.restofleet.server.domain.ingest.IngestResult
import ru.restofleet.server.domain.ingest.TelemetryRepository
import ru.restofleet.shared.kernel.Clock

/**
 * Пакетная запись телеметрии, проб и событий одной транзакцией (FR-BE-11). Сообщения неизвестных и выведенных из
 * эксплуатации устройств отбрасываются с подсчётом; у остальных обновляется `last_seen_at`. Ack брокеру — после
 * возврата (commit), это делает вызывающий ingest (P10).
 */
class IngestBatch(
    private val devices: DeviceRepository,
    private val telemetry: TelemetryRepository,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(batch: DeviceDataBatch): IngestResult {
        if (batch.size == 0) return IngestResult(0, 0, 0, 0)
        return tx.inTransaction {
            val active = devices.activeIds(batch.deviceIds())
            val accepted = batch.onlyFrom(active)
            val result = IngestResult(
                telemetry = if (accepted.telemetry.isEmpty()) 0 else telemetry.insertTelemetry(accepted.telemetry),
                probeResults = if (accepted.probes.isEmpty()) 0 else telemetry.insertProbes(accepted.probes),
                events = if (accepted.events.isEmpty()) 0 else telemetry.insertEvents(accepted.events),
                dropped = batch.size - accepted.size,
            )
            if (active.isNotEmpty()) devices.touch(active, clock.nowMillis())
            result
        }
    }
}
