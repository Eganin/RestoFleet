package ru.restofleet.server.domain.polling.usecase

import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.polling.PollingSettings
import ru.restofleet.shared.kernel.Clock

/**
 * Планировщик (каждые 5 с, `server:app`; реш. 214): устройство в режиме REST-polling без опроса дольше
 * [PollingSettings.staleAfterMillis] → `offline`. MQTT-устройства не трогает (их presence — по LWT).
 */
class MarkStalePollers(
    private val devices: DeviceRepository,
    private val settings: PollingSettings,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int =
        tx.inTransaction { devices.markStalePollersOffline(clock.nowMillis() - settings.staleAfterMillis) }
}
