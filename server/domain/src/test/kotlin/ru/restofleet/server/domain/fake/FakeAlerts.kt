package ru.restofleet.server.domain.fake

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import ru.restofleet.server.domain.alert.Alert
import ru.restofleet.server.domain.alert.AlertConditionRepository
import ru.restofleet.server.domain.alert.AlertCounts
import ru.restofleet.server.domain.alert.AlertFilter
import ru.restofleet.server.domain.alert.AlertRepository
import ru.restofleet.server.domain.alert.AlertView
import ru.restofleet.server.domain.alert.Condition
import ru.restofleet.server.domain.alert.ConditionKey
import ru.restofleet.server.domain.alert.Severity
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.incident.ProbeStateRepository
import ru.restofleet.server.domain.ingest.ProbeReport
import ru.restofleet.server.domain.realtime.EventBus
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.shared.kernel.DeviceId

/** Состояние «устройство × цель» в фейке. */
data class StoredProbeState(val down: Boolean, val since: Long, val lastResultAt: Long?)

class FakeAlertRepository(private val db: InMemoryDb) : AlertRepository {
    var lockedFor = mutableListOf<String>()

    override suspend fun insert(alert: Alert) = db.update { s ->
        check(s.alerts.values.none { it.isActive && it.subject == alert.subject }) { "unique active subject" }
        s.copy(alerts = s.alerts + (alert.id to alert))
    }

    override suspend fun update(alert: Alert) = db.update { it.copy(alerts = it.alerts + (alert.id to alert)) }

    override suspend fun findForUpdate(id: String): Alert? = db.read { it.alerts[id] }.also { lockedFor += id }

    override suspend fun active(deviceIds: Collection<DeviceId>): List<Alert> =
        db.read { s -> s.alerts.values.filter { it.isActive && it.deviceId in deviceIds } }

    override suspend fun list(filter: AlertFilter, page: PageRequest): List<AlertView> = db.read { s ->
        s.alerts.values
            .filter { filter.state == null || it.state == filter.state }
            .filter { !filter.activeOnly || it.isActive }
            .filter { filter.restaurantId == null || it.restaurantId == filter.restaurantId }
            .filter { filter.deviceId == null || it.deviceId == filter.deviceId }
            .filter { filter.rule == null || it.rule == filter.rule }
            .filter { filter.includeSuppressed || it.parentIncidentId == null }
            .sortedWith(compareByDescending<Alert> { it.openedAt }.thenByDescending { it.id })
            .drop(page.offset.toInt()).take(page.limit)
            .map { view(s, it) }
    }

    override suspend fun views(ids: Collection<String>): List<AlertView> =
        db.read { s -> ids.mapNotNull { s.alerts[it] }.map { view(s, it) } }

    override suspend fun activeCounts(): AlertCounts = db.read { s ->
        val active = s.alerts.values.filter { it.isActive && it.parentIncidentId == null }
        AlertCounts(active.size, active.count { it.severity == Severity.CRITICAL })
    }

    private fun view(s: DbState, alert: Alert): AlertView {
        val restaurant = s.restaurants[alert.restaurantId]
        return AlertView(alert, s.devices[alert.deviceId]?.name, restaurant?.code.orEmpty(), restaurant?.name.orEmpty())
    }
}

class FakeConditionRepository(private val db: InMemoryDb) : AlertConditionRepository {
    override suspend fun active(deviceIds: Collection<DeviceId>): List<Condition> =
        db.read { s -> s.conditions.values.filter { it.key.deviceId in deviceIds } }

    override suspend fun upsert(condition: Condition) =
        db.update { it.copy(conditions = it.conditions + (condition.key to condition)) }

    override suspend fun delete(key: ConditionKey) = db.update { it.copy(conditions = it.conditions - key) }
}

class FakeProbeStateRepository(private val db: InMemoryDb) : ProbeStateRepository {
    override suspend fun recordState(deviceId: DeviceId, target: String, down: Boolean, at: Long) = db.update { s ->
        val key = deviceId to target
        val old = s.probeStates[key]
        s.copy(probeStates = s.probeStates + (key to StoredProbeState(down, at, old?.lastResultAt)))
    }

    override suspend fun recordResults(reports: List<ProbeReport>) = db.update { s ->
        val updated = reports.flatMap { r -> r.results.map { (r.deviceId to it.target) to r.ts } }
            .fold(s.probeStates) { acc, (key, ts) ->
                val old = acc[key] ?: StoredProbeState(down = false, since = ts, lastResultAt = null)
                acc + (key to old.copy(lastResultAt = maxOf(old.lastResultAt ?: ts, ts)))
            }
        s.copy(probeStates = updated)
    }
}

/** Шина: запоминает события; [published] — после commit (проверяется тестами вместе с откатом). */
class FakeEventBus : EventBus {
    val published = mutableListOf<RealtimeEvent>()
    private val flow = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)

    override fun publish(event: RealtimeEvent) {
        published += event
        flow.tryEmit(event)
    }

    override fun events(): Flow<RealtimeEvent> = flow
}
