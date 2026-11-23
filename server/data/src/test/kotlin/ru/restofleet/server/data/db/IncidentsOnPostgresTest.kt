package ru.restofleet.server.data.db

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.Test
import ru.restofleet.server.data.realtime.SharedFlowEventBus
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.Samples.T0
import ru.restofleet.server.data.support.TestClock
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.server.domain.alert.AlertProcessor
import ru.restofleet.server.domain.alert.AlertState
import ru.restofleet.server.domain.alert.RuleId
import ru.restofleet.server.domain.alert.RuleParams
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.TimeRange
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.device.DeviceAnnouncement
import ru.restofleet.server.domain.device.DeviceTransport
import ru.restofleet.server.domain.incident.Incident
import ru.restofleet.server.domain.incident.IncidentFilter
import ru.restofleet.server.domain.incident.IncidentKind
import ru.restofleet.server.domain.incident.IncidentRepository
import ru.restofleet.server.domain.incident.IncidentSettings
import ru.restofleet.server.domain.incident.ResolveReason
import ru.restofleet.server.domain.incident.usecase.AcknowledgeIncident
import ru.restofleet.server.domain.incident.usecase.EvaluateIncidents
import ru.restofleet.server.domain.ingest.DeviceDataBatch
import ru.restofleet.server.domain.ingest.DeviceEvent
import ru.restofleet.server.domain.ingest.DeviceSignal
import ru.restofleet.server.domain.ingest.ProbeReport
import ru.restofleet.server.domain.ingest.ProbeResult
import ru.restofleet.server.domain.ingest.usecase.IngestBatch
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.UuidIdGenerator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Сценарии S8 на PostgreSQL (план 3.2): данные проб проходят через `IngestBatch` (события `probe_state` и результаты
 * проб), тик `EvaluateIncidents` — на настоящих репозиториях и `pg_try_advisory_xact_lock`.
 */
internal class IncidentsOnPostgresTest : PostgresTest() {
    private val clock = TestClock(T0)
    private val alerts = ExposedAlertRepository()
    private val incidents = ExposedIncidentRepository()
    private val probeStates = ExposedProbeStateRepository()
    private val bus = SharedFlowEventBus()
    private val ingest = IngestBatch(
        devices, ExposedTelemetryRepository(),
        AlertProcessor(alerts, ExposedAlertConditionRepository(), devices, UuidIdGenerator(), RuleParams()),
        alerts, probeStates, bus, TestDatabase.tx, clock,
    )
    private val tick = EvaluateIncidents(
        ExposedFleetQueries(), probeStates, incidents, alerts, ExposedProbeHistoryRepository(), restaurants,
        PostgresJobLock(), bus, TestDatabase.tx, clock, UuidIdGenerator(), IncidentSettings(),
    )

    private lateinit var fleet: Map<Restaurant, List<Device>>

    private fun givenFleet(restaurantCount: Int = 12, perRestaurant: Int = 3) {
        fleet = (1..restaurantCount).associate { n ->
            val r = givenRestaurant("S8-%02d".format(n))
            r to (1..perRestaurant).map { givenDevice(r) }
        }
        tx {
            fleet.values.flatten().forEach {
                devices.markOnline(it.id, clock.now, DeviceTransport.MQTT, DeviceAnnouncement())
            }
        }
    }

    /** Отчёт каждого устройства ресторана: `api` по [apiDown], `ref` по [refDown] и события `probe_state`. */
    private fun report(restaurant: Restaurant, apiDown: Boolean, refDown: Boolean = false) = runBlocking {
        val now = clock.now
        val devicesOf = fleet.getValue(restaurant)
        fun result(target: String, down: Boolean) =
            ProbeResult(
                target, !down, if (down) null else 204, if (down) "timeout" else null, 10, null, null, null, null,
            )
        ingest(
            DeviceDataBatch(
                probes = devicesOf.map {
                    ProbeReport(it.id, now, listOf(result("api", apiDown), result("ref", refDown)))
                },
                events = devicesOf.flatMap { d ->
                    listOf(
                        event(d, now, "api", apiDown),
                        event(d, now, "ref", refDown),
                    )
                },
            ),
        )
    }

    private fun event(d: Device, now: Long, target: String, down: Boolean) =
        DeviceEvent(d.id, now, "probe_state", "{}", DeviceSignal.ProbeState(target, down, "timeout", 3))

    private fun tickAt(offsetMs: Long, reports: () -> Unit) {
        clock.now += offsetMs
        reports()
        runBlocking { tick() }
    }

    private fun open() = tx { incidents.open() }

    @Test
    fun `S8a global backend outage is one R-08 incident and R-05 alerts are suppressed`() {
        givenFleet()
        tickAt(30_000) { fleet.keys.forEach { report(it, apiDown = true) } }

        val incident = open().single()
        assertThat(incident.kind).isEqualTo(IncidentKind.BACKEND_GLOBAL)
        assertThat(incident.restaurants.toSet()).isEqualTo(fleet.keys.map { it.id }.toSet())
        assertThat(incident.evidence.thresholds.n).isEqualTo(12)
        val r05 = tx { alerts.active(fleet.values.flatten().map { it.id }) }.filter { it.rule == RuleId.R05 }
        assertThat(r05).hasSize(36)
        assertThat(r05.map { it.parentIncidentId }.toSet()).containsExactly(incident.id)
        assertThat(tx { incidents.suppressedCounts(listOf(incident.id)) }).containsExactly(incident.id, 36)
        assertThat(tx { alerts.activeCounts() }.active).isEqualTo(0)
    }

    @Test
    fun `S8b restaurant network is R-07 only for it`() {
        givenFleet()
        val broken = fleet.keys.first()
        tickAt(30_000) { fleet.keys.forEach { report(it, apiDown = it == broken, refDown = it == broken) } }

        val incident = open().single()
        assertThat(incident.kind).isEqualTo(IncidentKind.RESTAURANT_NETWORK)
        assertThat(incident.restaurants).containsExactly(broken.id)
        assertThat(incident.evidence.restaurants.single().refUp).isEqualTo(0)
    }

    @Test
    fun `S8c two restaurants down with ref alive are two R-09 and clear after two ticks`() {
        givenFleet()
        val (a, b) = fleet.keys.take(2)
        tickAt(30_000) { fleet.keys.forEach { report(it, apiDown = it == a || it == b) } }

        assertThat(open().map { it.kind }.toSet()).containsExactly(IncidentKind.BACKEND_LOCAL)
        assertThat(open().flatMap { it.restaurants }.toSet()).containsExactly(a.id, b.id)
        val history = tx { ProbeAgg1mTable.selectAll().count() }
        assertThat(history).isEqualTo(24L)

        tickAt(30_000) { fleet.keys.forEach { report(it, apiDown = false) } }
        assertThat(open()).hasSize(2)
        tickAt(30_000) { fleet.keys.forEach { report(it, apiDown = false) } }
        assertThat(open()).isEmpty()
        val closed = tx { incidents.list(IncidentFilter(open = false), PageRequest.of()) }
        assertThat(closed.map { it.resolveReason }.toSet()).containsExactly(ResolveReason.CLEARED)
        assertThat(tx { incidents.list(IncidentFilter(range = TimeRange(from = clock.now + 1)), PageRequest.of()) })
            .isEmpty()
        assertThat(tx { incidents.list(IncidentFilter(range = TimeRange(to = T0)), PageRequest.of()) }).isEmpty()
        assertThat(tx { incidents.list(IncidentFilter(open = true), PageRequest.of()) }).isEmpty()
    }

    @Test
    fun `incident row round trips and acknowledgement is stored`() {
        givenFleet(restaurantCount = 4)
        tickAt(30_000) {
            report(fleet.keys.first(), apiDown = true)
            fleet.keys.drop(1).forEach { report(it, false) }
        }
        val incident = open().single()

        val acked = incident.copy(ackBy = "support", ackAt = clock.now, comment = "звоним")
        tx { incidents.update(acked) }
        assertThat(tx { incidents.find(incident.id) }).isEqualTo(acked)
        assertThat(tx { incidents.findForUpdate(incident.id) }).isEqualTo(acked)
        assertThat(tx { incidents.find("not-a-uuid") }).isNull()
        assertThat(tx { incidents.findForUpdate("not-a-uuid") }).isNull()
        assertThat(tx { incidents.suppressedCounts(listOf("not-a-uuid")) }).isEmpty()
        assertThat(acked.evidence.restaurants.single().errorClasses).containsExactly("timeout", 3)
    }

    /**
     * RV-5: подтверждение коммитится отдельной транзакцией после того, как тик прочитал открытые инциденты, но до его
     * записи. Тик пишет только свои столбцы — подтверждение и комментарий остаются, а в WS уходит строка с ними.
     */
    @Test
    fun `acknowledgement committed between the tick read and write survives the tick`() {
        givenFleet(restaurantCount = 4)
        tickAt(30_000) { fleet.keys.forEach { report(it, apiDown = it == fleet.keys.first()) } }
        val incident = open().single()
        val acknowledge = AcknowledgeIncident(incidents, restaurants, bus, TestDatabase.tx, clock)
        val racing = object : IncidentRepository by incidents {
            override suspend fun open(): List<Incident> = incidents.open().also {
                // Другой поток — другое соединение: чтение тика блокировок не берёт, подтверждение не ждёт
                val acked = CompletableFuture.supplyAsync {
                    runBlocking { acknowledge(incident.id, "support", "звоним") }
                }
                assertThat(acked.get(30, TimeUnit.SECONDS)).isInstanceOf(Outcome.Success::class.java)
            }
        }
        val racingTick = EvaluateIncidents(
            ExposedFleetQueries(), probeStates, racing, alerts, ExposedProbeHistoryRepository(), restaurants,
            PostgresJobLock(), bus, TestDatabase.tx, clock, UuidIdGenerator(), IncidentSettings(),
        )
        clock.now += 30_000
        fleet.keys.forEach { report(it, apiDown = false) }
        runBlocking { racingTick() }

        val stored = tx { incidents.find(incident.id) }!!
        assertThat(stored.missedTicks).isEqualTo(1)
        assertThat(stored.ackBy).isEqualTo("support")
        assertThat(stored.comment).isEqualTo("звоним")
    }

    @Test
    fun `tick write keeps acknowledgement columns and alert auto resolve keeps everything but its own`() {
        givenFleet(restaurantCount = 4)
        tickAt(30_000) { fleet.keys.forEach { report(it, apiDown = it == fleet.keys.first()) } }
        val incident = open().single()
        tx { incidents.update(incident.copy(ackBy = "support", ackAt = clock.now, comment = "звоним")) }

        tx { incidents.updateEvaluation(incident.copy(missedTicks = 1, resolvedAt = clock.now, ackBy = null)) }
        val evaluated = tx { incidents.find(incident.id) }!!
        assertThat(evaluated.missedTicks).isEqualTo(1)
        assertThat(evaluated.resolvedAt).isEqualTo(clock.now)
        assertThat(evaluated.ackBy).isEqualTo("support")
        assertThat(evaluated.comment).isEqualTo("звоним")

        val r05 = tx { alerts.active(fleet.values.flatten().map { it.id }) }.first { it.rule == RuleId.R05 }
        val acked = r05.copy(state = AlertState.ACKNOWLEDGED, ackBy = "support", ackAt = clock.now, comment = "смотрим")
        tx { alerts.update(acked) }
        assertThat(tx { alerts.autoResolve(r05.id, clock.now + 1) }).isTrue()
        val resolved = tx { alerts.views(listOf(r05.id)) }.single().alert
        assertThat(resolved).isEqualTo(acked.copy(state = AlertState.RESOLVED, resolvedAt = clock.now + 1, auto = true))
        assertThat(tx { alerts.autoResolve(r05.id, clock.now + 2) }).isFalse()
        assertThat(tx { alerts.autoResolve("not-a-uuid", clock.now) }).isFalse()
    }

    @Test
    fun `advisory lock is exclusive between concurrent transactions and released on commit`() {
        val lock = PostgresJobLock()
        val (mine, other) = TestDatabase.inTx {
            val held = lock.tryLock(LOCK)
            val fromOtherSession = TestDatabase.dataSource.connection.use { c ->
                c.createStatement().use { st ->
                    val sql = "SELECT pg_try_advisory_xact_lock($LOCK)"
                    st.executeQuery(sql).use { rs -> rs.next() && rs.getBoolean(1) }
                }.also { c.rollback() }
            }
            held to fromOtherSession
        }
        assertThat(mine).isTrue()
        assertThat(other).isFalse()
        assertThat(TestDatabase.inTx { lock.tryLock(LOCK) }).isTrue()
    }

    private companion object {
        const val LOCK = 42L
    }
}
