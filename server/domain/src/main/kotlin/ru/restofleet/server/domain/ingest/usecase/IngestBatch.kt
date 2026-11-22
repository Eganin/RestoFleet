package ru.restofleet.server.domain.ingest.usecase

import ru.restofleet.server.domain.alert.AlertProcessor
import ru.restofleet.server.domain.alert.AlertRepository
import ru.restofleet.server.domain.alert.RuleInput
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.incident.ProbeStateRepository
import ru.restofleet.server.domain.ingest.DeviceDataBatch
import ru.restofleet.server.domain.ingest.DeviceSignal
import ru.restofleet.server.domain.ingest.IngestResult
import ru.restofleet.server.domain.ingest.TelemetryRepository
import ru.restofleet.server.domain.realtime.EventBus
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.shared.kernel.Clock

/**
 * Пакетная запись телеметрии, проб и событий одной транзакцией (FR-BE-11). Сообщения неизвестных и выведенных из
 * эксплуатации устройств отбрасываются с подсчётом; у остальных обновляется `last_seen_at`. В той же транзакции —
 * правила тревог ([AlertProcessor], реш. 414) и состояние «устройство × цель» для агрегатора (реш. 411). Ack брокеру —
 * после возврата (commit), это делает вызывающий ingest (P10); события шины — тоже после commit.
 */
class IngestBatch(
    private val devices: DeviceRepository,
    private val telemetry: TelemetryRepository,
    private val alertProcessor: AlertProcessor,
    private val alerts: AlertRepository,
    private val probeStates: ProbeStateRepository,
    private val bus: EventBus,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(batch: DeviceDataBatch): IngestResult {
        if (batch.size == 0) return IngestResult(0, 0, 0, 0)
        var accepted = DeviceDataBatch()
        var changedAlerts = emptyList<String>()
        val result = tx.inTransaction {
            val active = devices.activeIds(batch.deviceIds())
            accepted = batch.onlyFrom(active)
            val result = IngestResult(
                telemetry = if (accepted.telemetry.isEmpty()) 0 else telemetry.insertTelemetry(accepted.telemetry),
                probeResults = if (accepted.probes.isEmpty()) 0 else telemetry.insertProbes(accepted.probes),
                events = if (accepted.events.isEmpty()) 0 else telemetry.insertEvents(accepted.events),
                dropped = batch.size - accepted.size,
            )
            if (active.isNotEmpty()) devices.touch(active, clock.nowMillis())
            if (accepted.probes.isNotEmpty()) probeStates.recordResults(accepted.probes)
            accepted.events.forEach { event ->
                (event.signal as? DeviceSignal.ProbeState)?.let {
                    probeStates.recordState(event.deviceId, it.target, it.down, event.ts)
                }
            }
            changedAlerts = alertProcessor.process(ruleInputs(accepted))
            result
        }
        accepted.telemetry.forEach { bus.publish(RealtimeEvent.TelemetryReceived(it)) }
        publishAlerts(changedAlerts)
        return result
    }

    private suspend fun publishAlerts(ids: List<String>) {
        if (ids.isEmpty()) return
        tx.inTransaction { alerts.views(ids) }.forEach { bus.publish(RealtimeEvent.AlertChanged(it)) }
    }

    private fun ruleInputs(batch: DeviceDataBatch): List<RuleInput> =
        batch.telemetry.map { RuleInput.Telemetry(it.deviceId, it.ts, it.charging, it.freeMb) } +
            batch.events.mapNotNull { event -> event.signal?.let { RuleInput.Event(event.deviceId, event.ts, it) } }
}
