package ru.restofleet.server.domain.incident.usecase

import ru.restofleet.server.domain.alert.AlertRepository
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.device.FleetQueries
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.incident.FleetDevice
import ru.restofleet.server.domain.incident.Incident
import ru.restofleet.server.domain.incident.IncidentClassifier
import ru.restofleet.server.domain.incident.IncidentReconciler
import ru.restofleet.server.domain.incident.IncidentRepository
import ru.restofleet.server.domain.incident.IncidentSettings
import ru.restofleet.server.domain.incident.JobLock
import ru.restofleet.server.domain.incident.ProbeAggregator
import ru.restofleet.server.domain.incident.ProbeHistoryRepository
import ru.restofleet.server.domain.incident.ProbeStateRepository
import ru.restofleet.server.domain.incident.Suppression
import ru.restofleet.server.domain.incident.views
import ru.restofleet.server.domain.realtime.EventBus
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.server.domain.restaurant.RestaurantRepository
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.IdGenerator

/**
 * Тик инцидентов (FR-BE-35…37; реш. 409–412, 416): под межпроцессной блокировкой транзакции — агрегатор, история
 * минуты, классификатор, сверка с открытыми инцидентами, пересчёт подавления. Если блокировку держит другой экземпляр,
 * тик пропускается. После commit в шину уходят открытые, закрытые и изменившие состав инциденты и тревоги, у которых
 * сменился родитель. Возвращает число таких инцидентов.
 */
@Suppress("LongParameterList")
class EvaluateIncidents(
    private val devices: FleetQueries,
    private val probeStates: ProbeStateRepository,
    private val incidents: IncidentRepository,
    private val alerts: AlertRepository,
    private val history: ProbeHistoryRepository,
    private val restaurants: RestaurantRepository,
    private val lock: JobLock,
    private val bus: EventBus,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val settings: IncidentSettings,
) {
    suspend operator fun invoke(): Int {
        val now = clock.nowMillis()
        var notify = emptyList<Incident>()
        var reparented = emptyList<String>()
        tx.inTransaction {
            if (!lock.tryLock(LOCK_KEY)) return@inTransaction
            val fleet = devices.fleet(seenSince = now - settings.silentAfterMillis)
                .map { FleetDevice(it.id, it.restaurantId, it.status == DeviceStatus.ONLINE) }
            val stats = ProbeAggregator.aggregate(fleet, probeStates.fresh(since = now - settings.windowMillis))
            history.record(now - now % MINUTE, stats)
            val classification = IncidentClassifier.classify(stats, settings.classifier)
            val open = incidents.open()
            val result = IncidentReconciler.reconcile(
                open, classification, stats, now, ids::newId, settings.classifier, settings.resolveAfterTicks,
            )
            result.opened.forEach { incidents.insert(it) }
            result.changed.forEach { incidents.updateEvaluation(it) }
            val stillOpen = result.all.filter { it.isOpen }
            val candidates = (alerts.activeInRestaurants(stillOpen.flatMap { it.restaurants }.toSet()) +
                alerts.activeSuppressed()).distinctBy { it.id }
            val changes = Suppression.changes(stillOpen, candidates, settings.classifier)
            changes.forEach { (alertId, parent) -> alerts.setParent(alertId, parent) }
            val before = open.associateBy { it.id }
            notify = result.opened + result.changed.filter { changed ->
                val old = before.getValue(changed.id)
                !changed.isOpen || old.restaurants.toSet() != changed.restaurants.toSet()
            }
            reparented = changes.keys.toList()
        }
        publish(notify, reparented)
        return notify.size
    }

    private suspend fun publish(changed: List<Incident>, reparented: List<String>) {
        if (changed.isEmpty() && reparented.isEmpty()) return
        val (incidentViews, alertViews) = tx.inTransaction {
            // Строки после commit, а не снимок тика: подтверждение могло прийти между чтением и записью (RV-5)
            val current = changed.mapNotNull { incidents.find(it.id) }
            val counts = incidents.suppressedCounts(current.map { it.id })
            views(current, restaurants.list(), counts) to alerts.views(reparented)
        }
        incidentViews.forEach { bus.publish(RealtimeEvent.IncidentChanged(it)) }
        alertViews.forEach { bus.publish(RealtimeEvent.AlertChanged(it)) }
    }

    companion object {
        /** Ключ `pg_try_advisory_xact_lock` тика инцидентов. */
        const val LOCK_KEY = 0x5246_0001L
        private const val MINUTE = 60_000L
    }
}
