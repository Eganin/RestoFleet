package ru.restofleet.agent.data.outbox

import androidx.room.withTransaction
import ru.restofleet.agent.data.shadow.FileShadowStore
import ru.restofleet.agent.domain.port.DeviceDataReset

/**
 * Данные прежнего устройства (R-3, реш. 304): outbox и журнал команд — одной транзакцией Room, затем файл тени.
 * Ошибка пробрасывается: `Provision` тогда не сохраняет новую учётку (`storage_failed`).
 */
internal class LocalDeviceDataReset(
    private val db: AgentDatabase,
    private val shadow: FileShadowStore,
) : DeviceDataReset {
    override suspend fun clear() {
        db.withTransaction {
            db.outboxDao().deleteAll()
            db.processedCommandDao().deleteAll()
        }
        shadow.clear()
    }
}
