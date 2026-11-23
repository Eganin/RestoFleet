package ru.restofleet.server.domain.incident

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.alert.Alert
import ru.restofleet.server.domain.alert.AlertState
import ru.restofleet.server.domain.alert.AlertSubject
import ru.restofleet.server.domain.alert.RuleId
import ru.restofleet.server.domain.alert.usecase.GetSummary
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.common.TimeRange
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.fake.StoredProbeState
import ru.restofleet.server.domain.fake.World
import ru.restofleet.server.domain.fake.World.Companion.deviceId
import ru.restofleet.server.domain.incident.usecase.AcknowledgeIncident
import ru.restofleet.server.domain.incident.usecase.GetIncident
import ru.restofleet.server.domain.incident.usecase.ListIncidents
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.Outcome

/**
 * Жизненный цикл инцидентов (план 3.1, реш. 412, 416): переходы «локально → глобально → локально», закрытие после
 * двух тиков, подавление и перенос дочерних тревог, блокировка тика, выдача и подтверждение.
 */
class IncidentLifecycleTest {
    private val w = World()
    private val tick = w.evaluateIncidents()
    private val restaurantIds = (1..12).map { "r-%02d".format(it) }

    init {
        w.clock.now = 200_000_000
        restaurantIds.forEach { r ->
            w.restaurant(r, r.uppercase())
            (1..3).forEach { n ->
                w.device("d-$r-$n", restaurantId = r, status = DeviceStatus.ONLINE, lastSeenAt = w.clock.now)
            }
        }
    }

    /** Все устройства ресторана видят `api` недоступным (или доступным) и `ref` доступным, результаты свежие. */
    private fun api(restaurant: String, down: Boolean) {
        (1..3).forEach { n ->
            val id = deviceId("d-$restaurant-$n")
            val apiState = StoredProbeState(down, w.clock.now, w.clock.now, if (down) "timeout" else null)
            val refState = StoredProbeState(false, w.clock.now, w.clock.now)
            w.seed { it.copy(probeStates = it.probeStates + ((id to "api") to apiState) + ((id to "ref") to refState)) }
        }
    }

    private fun allHealthy() = restaurantIds.forEach { api(it, down = false) }

    private suspend fun advance() {
        w.clock.now += 30_000
        // результаты проб свежие на каждом тике
        w.seed { s -> s.copy(probeStates = s.probeStates.mapValues { (_, v) -> v.copy(lastResultAt = w.clock.now) }) }
        tick()
    }

    private fun open() = w.db.state.incidents.values.filter { it.isOpen }

    private fun r05(restaurant: String, n: Int = 1): Alert {
        val alert = Alert(
            "a-$restaurant-$n", AlertSubject(RuleId.R05, deviceId("d-$restaurant-$n"), "api"), RestaurantId(restaurant),
            AlertState.OPEN, w.clock.now,
        )
        w.seed { it.copy(alerts = it.alerts + (alert.id to alert)) }
        return alert
    }

    @Test
    fun localToGlobalToLocalAndExitAfterTwoTicks() = runTest {
        allHealthy()
        api("r-11", down = true)
        api("r-12", down = true)
        val child = r05("r-11")
        advance()

        val locals = open()
        assertThat(locals.map { it.kind }.toSet()).containsExactly(IncidentKind.BACKEND_LOCAL)
        assertThat(locals.flatMap { it.restaurants }.map { it.value }).containsExactly("r-11", "r-12")
        val localOfChild = locals.single { RestaurantId("r-11") in it.restaurants }
        assertThat(w.db.state.alerts.getValue(child.id).parentIncidentId).isEqualTo(localOfChild.id)

        api("r-10", down = true)
        advance()
        val global = open().single()
        assertThat(global.kind).isEqualTo(IncidentKind.BACKEND_GLOBAL)
        assertThat(global.restaurants.map { it.value }).containsExactly("r-10", "r-11", "r-12")
        assertThat(w.db.state.incidents.getValue(localOfChild.id).resolveReason).isEqualTo(ResolveReason.SUPERSEDED)
        assertThat(w.db.state.alerts.getValue(child.id).parentIncidentId).isEqualTo(global.id)

        api("r-10", down = false)
        advance()
        val back = open()
        assertThat(back.map { it.kind }.toSet()).containsExactly(IncidentKind.BACKEND_LOCAL)
        assertThat(w.db.state.incidents.getValue(global.id).resolveReason).isEqualTo(ResolveReason.SUPERSEDED)
        val newLocal = back.single { RestaurantId("r-11") in it.restaurants }
        assertThat(w.db.state.alerts.getValue(child.id).parentIncidentId).isEqualTo(newLocal.id)

        allHealthy()
        advance()
        assertThat(open()).hasSize(2)
        assertThat(open().map { it.missedTicks }.toSet()).containsExactly(1)
        advance()
        assertThat(open()).isEmpty()
        assertThat(w.db.state.incidents.getValue(newLocal.id).resolveReason).isEqualTo(ResolveReason.CLEARED)
        assertThat(w.db.state.alerts.getValue(child.id).parentIncidentId).isNull()
    }

    @Test
    fun conditionReturningWithinHysteresisKeepsTheSameIncident() = runTest {
        allHealthy()
        api("r-12", down = true)
        advance()
        val first = open().single()
        api("r-12", down = false)
        advance()
        api("r-12", down = true)
        advance()
        assertThat(open().single().id).isEqualTo(first.id)
        assertThat(open().single().missedTicks).isEqualTo(0)
    }

    @Test
    fun networkIncidentSuppressesOfflineAndProbeAlertsOfTheRestaurant() = runTest {
        allHealthy()
        (1..2).forEach { n ->
            val id = deviceId("d-r-05-$n")
            w.seed { s ->
                s.copy(devices = s.devices + (id to s.devices.getValue(id).copy(status = DeviceStatus.OFFLINE)))
            }
        }
        val offline = Alert(
            "a-off", AlertSubject(RuleId.R01, deviceId("d-r-05-1")), RestaurantId("r-05"), AlertState.OPEN, 1,
        )
        val battery = Alert(
            "a-bat", AlertSubject(RuleId.R02, deviceId("d-r-05-3")), RestaurantId("r-05"), AlertState.OPEN, 1,
        )
        val kds = r05("r-05", 3).copy(id = "a-kds", subject = AlertSubject(RuleId.R05, deviceId("d-r-05-3"), "kds"))
        w.seed { it.copy(alerts = it.alerts + mapOf(offline.id to offline, battery.id to battery, kds.id to kds)) }
        advance()

        val network = open().single()
        assertThat(network.kind).isEqualTo(IncidentKind.RESTAURANT_NETWORK)
        assertThat(network.target).isEqualTo("ref")
        val parents = w.db.state.alerts.mapValues { it.value.parentIncidentId }
        assertThat(parents.getValue("a-off")).isEqualTo(network.id)
        assertThat(parents.getValue("a-kds")).isEqualTo(network.id)
        assertThat(parents.getValue("a-bat")).isNull()
        assertThat(network.evidence.restaurants.single().devicesOnline).isEqualTo(1)
    }

    @Test
    fun tickIsSkippedWhileAnotherInstanceHoldsTheLock() = runTest {
        allHealthy()
        api("r-12", down = true)
        w.lock.held = true
        advance()
        assertThat(w.db.state.incidents).isEmpty()
        assertThat(w.db.state.history).isEmpty()
        w.lock.held = false
        advance()
        assertThat(w.db.state.incidents).hasSize(1)
        assertThat(w.db.state.history.keys.single() % 60_000).isEqualTo(0)
    }

    @Test
    fun silentDevicesAndStaleResultsAreIgnored() = runTest {
        allHealthy()
        api("r-12", down = true)
        w.seed { s ->
            s.copy(
                devices = s.devices.mapValues { (_, d) ->
                    if (d.restaurantId.value == "r-12") d.copy(lastSeenAt = 0) else d
                },
            )
        }
        advance()
        assertThat(w.db.state.incidents).isEmpty()
        assertThat(w.db.state.history.values.single().none { it.restaurantId.value == "r-12" }).isTrue()
    }

    @Test
    fun eventsArePublishedForOpenedAndResolvedOnly() = runTest {
        allHealthy()
        api("r-12", down = true)
        r05("r-12")
        advance()
        advance()
        val incidentEvents = w.bus.published.filterIsInstance<RealtimeEvent.IncidentChanged>()
        assertThat(incidentEvents).hasSize(1)
        assertThat(incidentEvents.single().view.suppressedAlerts).isEqualTo(1)
        assertThat(incidentEvents.single().view.restaurants.single().code).isEqualTo("R-12")
        assertThat(w.bus.published.filterIsInstance<RealtimeEvent.AlertChanged>()).hasSize(1)
        assertThat(tick()).isEqualTo(0)
    }

    /**
     * V-6: рестораны восстанавливаются в разные тики — состав глобального инцидента накапливается (охват за время
     * жизни), а «сейчас затронутые» — в доказательствах и в сводке.
     */
    @Test
    fun globalIncidentKeepsEveryRestaurantItEverCoveredWhileEvidenceAndSummaryShowTheCurrentOnes() = runTest {
        restaurantIds.forEach { api(it, down = true) }
        advance()
        val global = open().single()
        assertThat(global.restaurants).hasSize(12)

        restaurantIds.take(6).forEach { api(it, down = false) }
        advance()
        val narrowed = w.db.state.incidents.getValue(global.id)
        assertThat(narrowed.isOpen).isTrue()
        assertThat(narrowed.restaurants.map { it.value }).containsExactlyElementsIn(restaurantIds)
        assertThat(narrowed.evidence.restaurants.map { it.restaurantId.value })
            .containsExactlyElementsIn(restaurantIds.drop(6))
        val summary = GetSummary(w.fleet, w.alerts, w.restaurants, w.incidents, w.tx, w.clock)()
        assertThat(summary.problems.map { it.restaurant.id.value }).containsExactlyElementsIn(restaurantIds.drop(6))

        allHealthy()
        advance()
        advance()
        val closed = w.db.state.incidents.getValue(global.id)
        assertThat(closed.resolveReason).isEqualTo(ResolveReason.CLEARED)
        assertThat(closed.restaurants).hasSize(12)
    }

    /** RV-5: подтверждение, закоммиченное между чтением открытых инцидентов и записью тика, не затирается. */
    @Test
    fun acknowledgementCommittedWhileTheTickRunsIsKept() = runTest {
        allHealthy()
        api("r-12", down = true)
        advance()
        val id = open().single().id
        w.incidents.afterOpenRead = {
            w.seed { s ->
                val acked = s.incidents.getValue(id).copy(ackBy = "support", ackAt = w.clock.now, comment = "звоним")
                s.copy(incidents = s.incidents + (id to acked))
            }
        }
        api("r-12", down = false)
        advance()

        val stored = w.db.state.incidents.getValue(id)
        assertThat(stored.missedTicks).isEqualTo(1)
        assertThat(stored.ackBy).isEqualTo("support")
        assertThat(stored.comment).isEqualTo("звоним")
        advance()
        // в WS уходит строка после записи, а не снимок тика до неё
        val pushed = w.bus.published.filterIsInstance<RealtimeEvent.IncidentChanged>().last().view.incident
        assertThat(pushed.isOpen).isFalse()
        assertThat(pushed.ackBy).isEqualTo("support")
    }

    @Test
    fun listGetAcknowledgeAndSummary() = runTest {
        allHealthy()
        api("r-12", down = true)
        advance()
        val id = open().single().id
        val list = ListIncidents(w.incidents, w.restaurants, w.tx)
        val get = GetIncident(w.incidents, w.restaurants, w.tx)
        val ack = AcknowledgeIncident(w.incidents, w.restaurants, w.bus, w.tx, w.clock)

        assertThat(list(IncidentFilter(open = true)).single().incident.id).isEqualTo(id)
        assertThat(list(IncidentFilter(open = false))).isEmpty()
        assertThat(list(IncidentFilter(range = TimeRange(to = 0)))).isEmpty()
        val details = (get(id) as Outcome.Success).value
        assertThat(details.incident.evidence.restaurants.single().targetDown).isEqualTo(3)
        assertThat(details.incident.evidence.otherRestaurantsOk).isEqualTo(11)
        assertThat(get("nope")).isEqualTo(Outcome.Failure(ServerError.NotFound("incident", "nope")))

        assertThat(ack(id, "support", "x".repeat(501)))
            .isEqualTo(Outcome.Failure(ServerError.Validation("comment", "too_long")))
        val acked = (ack(id, "support", " звоним провайдеру ") as Outcome.Success).value.incident
        assertThat(acked.ackBy).isEqualTo("support")
        assertThat(acked.comment).isEqualTo("звоним провайдеру")
        assertThat(ack(id, "support", null)).isEqualTo(Outcome.Failure(ServerError.Conflict("already_acknowledged")))
        assertThat(ack("nope", "support", null)).isEqualTo(Outcome.Failure(ServerError.NotFound("incident", "nope")))

        val summary = GetSummary(w.fleet, w.alerts, w.restaurants, w.incidents, w.tx, w.clock)()
        assertThat(summary.incidentsOpen).isEqualTo(1)
        val problem = summary.problems.first()
        assertThat(problem.rule).isEqualTo(RuleId.R09)
        assertThat(problem.count).isEqualTo(3)
        assertThat(problem.restaurant.code).isEqualTo("R-12")

        allHealthy()
        advance()
        advance()
        assertThat(ack(id, "support", null)).isEqualTo(Outcome.Failure(ServerError.Conflict("already_resolved")))
        assertThat(list(IncidentFilter(open = false)).single().incident.resolveReason).isEqualTo(ResolveReason.CLEARED)
    }

    @Test
    fun codes() {
        assertThat(IncidentKind.fromCode("backend_local")).isEqualTo(IncidentKind.BACKEND_LOCAL)
        assertThat(IncidentKind.fromCode("x")).isNull()
        assertThat(ResolveReason.fromCode("superseded")).isEqualTo(ResolveReason.SUPERSEDED)
        assertThat(ResolveReason.fromCode(null)).isNull()
        assertThat(IncidentKind.entries.map { it.rule.code }).containsExactly("R-07", "R-08", "R-09").inOrder()
    }
}
