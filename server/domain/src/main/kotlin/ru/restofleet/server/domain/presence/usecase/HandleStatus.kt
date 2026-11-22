package ru.restofleet.server.domain.presence.usecase

import ru.restofleet.server.domain.alert.AlertProcessor
import ru.restofleet.server.domain.alert.AlertRepository
import ru.restofleet.server.domain.alert.RuleInput
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.device.DeviceTransport
import ru.restofleet.server.domain.device.MessagePublisher
import ru.restofleet.server.domain.ingest.IngestOutcome
import ru.restofleet.server.domain.presence.DeviceSession
import ru.restofleet.server.domain.presence.PresenceState
import ru.restofleet.server.domain.presence.SessionCloseReason
import ru.restofleet.server.domain.presence.SessionRepository
import ru.restofleet.server.domain.presence.StatusEffects
import ru.restofleet.server.domain.presence.StatusReport
import ru.restofleet.server.domain.realtime.EventBus
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.server.domain.shadow.ShadowRepository
import ru.restofleet.shared.kernel.Clock

/**
 * `status` устройства (FR-BE-12, P3, P4). `online` открывает сессию `connId` (прочие открытые закрываются как
 * `replaced`) и ставит устройство `online`; `offline` (LWT или штатная остановка) закрывает сессию, **только если
 * `connId` совпадает с последней сессией** — запоздалый LWT старого подключения игнорируется. `online` повторно
 * не открывает уже закрытую сессию (передоставка после `offline`).
 *
 * После commit, если устройство не подтвердило текущий `desired`, он публикуется снова (реш. 233): так
 * восстанавливается публикация, не удавшаяся после commit в [ru.restofleet.server.domain.shadow.usecase.UpdateDesired].
 *
 * Применённый статус в той же транзакции проходит правила (R-01, реш. 414); смена статуса и тревоги уходят в шину
 * после commit.
 */
class HandleStatus(
    private val devices: DeviceRepository,
    private val sessions: SessionRepository,
    private val shadows: ShadowRepository,
    private val publisher: MessagePublisher,
    private val alertProcessor: AlertProcessor,
    private val alerts: AlertRepository,
    private val bus: EventBus,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(report: StatusReport): IngestOutcome {
        val effects = StatusEffects()
        val outcome = tx.inTransaction {
            val device = devices.findById(report.deviceId)
            if (device == null || device.isDecommissioned) return@inTransaction IngestOutcome.DROPPED_UNKNOWN_DEVICE
            val latest = sessions.latest(report.deviceId)
            when (report.state) {
                PresenceState.ONLINE -> if (latest?.connId == report.connId && !latest.isOpen) {
                    IngestOutcome.IGNORED_STALE
                } else {
                    online(device, report, effects)
                }
                PresenceState.OFFLINE -> if (latest != null && latest.connId != report.connId) {
                    IngestOutcome.IGNORED_STALE
                } else {
                    offline(device, report, hasSession = latest != null, effects)
                }
            }
        }
        effects.republish?.let { publisher.publishDesired(report.deviceId, it) }
        effects.status?.let { bus.publish(it) }
        if (effects.alerts.isNotEmpty()) {
            tx.inTransaction { alerts.views(effects.alerts) }.forEach { bus.publish(RealtimeEvent.AlertChanged(it)) }
        }
        return outcome
    }

    private suspend fun online(device: Device, report: StatusReport, effects: StatusEffects): IngestOutcome {
        val now = clock.nowMillis()
        sessions.closeOpen(report.deviceId, now, SessionCloseReason.REPLACED, exceptConnId = report.connId)
        sessions.open(DeviceSession(report.deviceId, report.connId, now, null, null))
        devices.markOnline(report.deviceId, now, DeviceTransport.MQTT, report.announcement)
        effects.republish = shadows.find(report.deviceId)?.takeIf { it.isPending }?.desiredState()
        effects.status =
            RealtimeEvent.DeviceStatusChanged(report.deviceId, device.restaurantId, DeviceStatus.ONLINE, now, null)
        effects.alerts = alertProcessor.process(listOf(RuleInput.StatusChanged(report.deviceId, now, true)))
        return IngestOutcome.APPLIED
    }

    private suspend fun offline(
        device: Device,
        report: StatusReport,
        hasSession: Boolean,
        effects: StatusEffects,
    ): IngestOutcome {
        val now = clock.nowMillis()
        val reason = report.reason ?: UNKNOWN
        if (hasSession) sessions.close(report.deviceId, report.connId, now, reason)
        devices.markOffline(report.deviceId, now)
        effects.status = RealtimeEvent.DeviceStatusChanged(
            report.deviceId, device.restaurantId, DeviceStatus.OFFLINE, device.lastSeenAt, reason,
        )
        effects.alerts = alertProcessor.process(listOf(RuleInput.StatusChanged(report.deviceId, now, false, reason)))
        return IngestOutcome.APPLIED
    }

    private companion object {
        const val UNKNOWN = "unknown"
    }
}
