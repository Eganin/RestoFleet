package ru.restofleet.server.data.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.restofleet.server.domain.presence.DeviceSession
import ru.restofleet.server.domain.presence.SessionRepository
import ru.restofleet.shared.kernel.DeviceId

/** `device_sessions`: строка на `(device_id, conn_id)`; повторный `online` — `ON CONFLICT DO NOTHING`. */
internal class ExposedSessionRepository : SessionRepository {
    override suspend fun open(session: DeviceSession) {
        DeviceSessionsTable.insertIgnore {
            it[deviceId] = session.deviceId.value
            it[connId] = session.connId.take(MAX_CONN_ID)
            it[connectedAt] = session.connectedAt.toTimestamp()
            it[disconnectedAt] = session.disconnectedAt?.toTimestamp()
            it[reason] = session.reason?.take(MAX_REASON)
        }
    }

    override suspend fun latest(deviceId: DeviceId): DeviceSession? = DeviceSessionsTable.selectAll()
        .where { DeviceSessionsTable.deviceId eq deviceId.value }
        .orderBy(DeviceSessionsTable.connectedAt, SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.toSession()

    override suspend fun close(deviceId: DeviceId, connId: String, at: Long, reason: String) {
        DeviceSessionsTable.update({
            (DeviceSessionsTable.deviceId eq deviceId.value) and
                (DeviceSessionsTable.connId eq connId.take(MAX_CONN_ID)) and
                DeviceSessionsTable.disconnectedAt.isNull()
        }) {
            it[disconnectedAt] = at.toTimestamp()
            it[DeviceSessionsTable.reason] = reason.take(MAX_REASON)
        }
    }

    override suspend fun closeOpen(deviceId: DeviceId, at: Long, reason: String, exceptConnId: String?) {
        DeviceSessionsTable.update({
            val open = (DeviceSessionsTable.deviceId eq deviceId.value) and DeviceSessionsTable.disconnectedAt.isNull()
            if (exceptConnId == null) open else open and (DeviceSessionsTable.connId neq exceptConnId.take(MAX_CONN_ID))
        }) {
            it[disconnectedAt] = at.toTimestamp()
            it[DeviceSessionsTable.reason] = reason.take(MAX_REASON)
        }
    }

    private fun ResultRow.toSession() = DeviceSession(
        deviceId = DeviceId.parse(this[DeviceSessionsTable.deviceId]) ?: error("invalid device id in database"),
        connId = this[DeviceSessionsTable.connId],
        connectedAt = this[DeviceSessionsTable.connectedAt].toMillis(),
        disconnectedAt = this[DeviceSessionsTable.disconnectedAt]?.toMillis(),
        reason = this[DeviceSessionsTable.reason],
    )

    private companion object {
        const val MAX_CONN_ID = 64
        const val MAX_REASON = 32
    }
}
