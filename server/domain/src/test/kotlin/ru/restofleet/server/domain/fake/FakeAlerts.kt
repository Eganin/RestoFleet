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
import ru.restofleet.server.domain.incident.Incident
import ru.restofleet.server.domain.incident.IncidentFilter
import ru.restofleet.server.domain.incident.IncidentRepository
import ru.restofleet.server.domain.incident.JobLock
import ru.restofleet.server.domain.incident.ProbeHistoryRepository
import ru.restofleet.server.domain.incident.ProbeObservation
import ru.restofleet.server.domain.incident.ProbeStateRepository
import ru.restofleet.server.domain.incident.RestaurantStats
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.ingest.ProbeReport
import ru.restofleet.server.domain.realtime.EventBus
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.shared.kernel.DeviceId

/** Состояние «устройство × цель» в фейке. */
data class StoredProbeState(val down: Boolean, val since: Long, val lastResultAt: Long?, val error: String? = null)

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

    override suspend fun activeInRestaurants(restaurantIds: Collection<RestaurantId>): List<Alert> =
        db.read { s -> s.alerts.values.filter { it.isActive && it.restaurantId in restaurantIds } }

    override suspend fun activeSuppressed(): List<Alert> =
        db.read { s -> s.alerts.values.filter { it.isActive && it.parentIncidentId != null } }

    override suspend fun setParent(alertId: String, parentIncidentId: String?) = db.update { s ->
        val alert = s.alerts.getValue(alertId)
        s.copy(alerts = s.alerts + (alertId to alert.copy(parentIncidentId = parentIncidentId)))
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
    override suspend fun recordState(deviceId: DeviceId, target: String, down: Boolean, error: String?, at: Long) =
        db.update { s ->
            val key = deviceId to target
            val old = s.probeStates[key]
            val since = if (old != null && old.down == down) old.since else at
            s.copy(probeStates = s.probeStates + (key to StoredProbeState(down, since, old?.lastResultAt, error)))
        }

    override suspend fun fresh(since: Long): List<ProbeObservation> = db.read { s ->
        s.probeStates.filter { (_, v) -> (v.lastResultAt ?: Long.MIN_VALUE) >= since }
            .map { (k, v) -> ProbeObservation(k.first, k.second, v.down, v.error) }
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

class FakeIncidentRepository(private val db: InMemoryDb) : IncidentRepository {
    override suspend fun insert(incident: Incident) =
        db.update { it.copy(incidents = it.incidents + (incident.id to incident)) }

    override suspend fun update(incident: Incident) = insert(incident)

    override suspend fun findForUpdate(id: String): Incident? = find(id)

    override suspend fun find(id: String): Incident? = db.read { it.incidents[id] }

    override suspend fun open(): List<Incident> = db.read { s -> s.incidents.values.filter { it.isOpen } }

    override suspend fun list(filter: IncidentFilter, page: PageRequest): List<Incident> = db.read { s ->
        s.incidents.values
            .filter { filter.open == null || it.isOpen == filter.open }
            .filter { filter.range.to == null || it.startedAt < filter.range.to!! }
            .filter { filter.range.from == null || (it.resolvedAt ?: Long.MAX_VALUE) >= filter.range.from!! }
            .sortedWith(compareByDescending<Incident> { it.startedAt }.thenByDescending { it.id })
            .drop(page.offset.toInt()).take(page.limit)
    }

    override suspend fun suppressedCounts(ids: Collection<String>): Map<String, Int> = db.read { s ->
        s.alerts.values.mapNotNull { it.parentIncidentId }.filter { it in ids }.groupingBy { it }.eachCount()
    }
}

class FakeProbeHistory(private val db: InMemoryDb) : ProbeHistoryRepository {
    override suspend fun record(bucket: Long, stats: List<RestaurantStats>) =
        db.update { it.copy(history = it.history + (bucket to stats)) }
}

/** Блокировка тика: [held] — её держит «другой экземпляр». */
class FakeJobLock(var held: Boolean = false) : JobLock {
    override suspend fun tryLock(key: Long): Boolean = !held
}
