package ru.restofleet.server.domain.fake

import ru.restofleet.server.domain.auth.RefreshToken
import ru.restofleet.server.domain.auth.User
import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.ingest.DeviceEvent
import ru.restofleet.server.domain.ingest.ProbeRecord
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.server.domain.presence.DeviceSession
import ru.restofleet.server.domain.provisioning.ProvisioningToken
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.shadow.Shadow
import ru.restofleet.shared.kernel.DeviceId

/** Учётка устройства в фейке. */
data class StoredCredential(val hash: String, val createdAt: Long, val revokedAt: Long?)

/** Неизменяемое состояние «БД»: откат транзакции — возврат к прежнему значению. */
data class DbState(
    val restaurants: Map<RestaurantId, Restaurant> = emptyMap(),
    val devices: Map<DeviceId, Device> = emptyMap(),
    val credentials: Map<DeviceId, StoredCredential> = emptyMap(),
    val tokens: Map<String, ProvisioningToken> = emptyMap(),
    val sessions: List<DeviceSession> = emptyList(),
    val shadows: Map<DeviceId, Shadow> = emptyMap(),
    val telemetry: List<TelemetrySnapshot> = emptyList(),
    val probes: List<ProbeRecord> = emptyList(),
    val events: List<DeviceEvent> = emptyList(),
    val commands: Map<String, Command> = emptyMap(),
    val users: Map<String, User> = emptyMap(),
    val refreshTokens: Map<String, RefreshToken> = emptyMap(),
)

/**
 * Общая «БД» фейков; репозитории требуют открытой транзакции (правило
 * [ru.restofleet.server.domain.common.TransactionRunner]).
 */
class InMemoryDb {
    var state = DbState()
    var inTransaction = false

    fun update(change: (DbState) -> DbState) {
        check(inTransaction) { "repository call outside transaction" }
        state = change(state)
    }

    fun <T> read(query: (DbState) -> T): T {
        check(inTransaction) { "repository call outside transaction" }
        return query(state)
    }
}
