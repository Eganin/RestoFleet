package ru.restofleet.server.domain.presence.usecase

import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.device.DeviceTransport
import ru.restofleet.server.domain.device.MessagePublisher
import ru.restofleet.server.domain.ingest.IngestOutcome
import ru.restofleet.server.domain.presence.DeviceSession
import ru.restofleet.server.domain.presence.PresenceState
import ru.restofleet.server.domain.presence.SessionCloseReason
import ru.restofleet.server.domain.presence.SessionRepository
import ru.restofleet.server.domain.presence.StatusReport
import ru.restofleet.server.domain.shadow.DesiredState
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
 */
class HandleStatus(
    private val devices: DeviceRepository,
    private val sessions: SessionRepository,
    private val shadows: ShadowRepository,
    private val publisher: MessagePublisher,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(report: StatusReport): IngestOutcome {
        var republish: DesiredState? = null
        val outcome = tx.inTransaction {
            val device = devices.findById(report.deviceId)
            if (device == null || device.isDecommissioned) return@inTransaction IngestOutcome.DROPPED_UNKNOWN_DEVICE
            val now = clock.nowMillis()
            val latest = sessions.latest(report.deviceId)
            when (report.state) {
                PresenceState.ONLINE -> if (latest?.connId == report.connId && !latest.isOpen) {
                    IngestOutcome.IGNORED_STALE
                } else {
                    sessions.closeOpen(report.deviceId, now, SessionCloseReason.REPLACED, exceptConnId = report.connId)
                    sessions.open(DeviceSession(report.deviceId, report.connId, now, null, null))
                    devices.markOnline(report.deviceId, now, DeviceTransport.MQTT, report.announcement)
                    republish = shadows.find(report.deviceId)?.takeIf { it.isPending }?.desiredState()
                    IngestOutcome.APPLIED
                }
                PresenceState.OFFLINE -> if (latest != null && latest.connId != report.connId) {
                    IngestOutcome.IGNORED_STALE
                } else {
                    if (latest != null) sessions.close(report.deviceId, report.connId, now, report.reason ?: UNKNOWN)
                    devices.markOffline(report.deviceId, now)
                    IngestOutcome.APPLIED
                }
            }
        }
        republish?.let { publisher.publishDesired(report.deviceId, it) }
        return outcome
    }

    private companion object {
        const val UNKNOWN = "unknown"
    }
}
