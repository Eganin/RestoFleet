package ru.restofleet.server.domain.alert

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.alert.usecase.AcknowledgeAlert
import ru.restofleet.server.domain.alert.usecase.ListAlerts
import ru.restofleet.server.domain.alert.usecase.ResolveAlert
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.fake.StoredProbeState
import ru.restofleet.server.domain.fake.World
import ru.restofleet.server.domain.ingest.DeviceDataBatch
import ru.restofleet.server.domain.ingest.DeviceEvent
import ru.restofleet.server.domain.ingest.DeviceSignal
import ru.restofleet.server.domain.ingest.ProbeReport
import ru.restofleet.server.domain.ingest.ProbeResult
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.server.domain.presence.PresenceState
import ru.restofleet.server.domain.presence.StatusReport
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.Outcome
import kotlin.test.assertFailsWith

/** Тревоги в приёме (реш. 414), жизненный цикл и выдача (план 2.1, 2.2). */
class AlertUseCasesTest {
    private val w = World()
    private val ingest = w.ingestBatch()
    private val status = w.handleStatus()
    private val ack = AcknowledgeAlert(w.alerts, w.tx, w.bus, w.clock)
    private val resolve = ResolveAlert(w.alerts, w.tx, w.bus, w.clock)
    private val list = ListAlerts(w.alerts, w.tx)
    private val d1 = World.deviceId("d-1")

    init {
        w.restaurant("r-1", "MSK-01")
        w.device("d-1", status = DeviceStatus.ONLINE)
    }

    private fun telemetry(ts: Long, freeMb: Long? = null, charging: Boolean? = null) = TelemetrySnapshot(
        d1, ts, ts, 50, charging, 30.0, "NONE", -60, freeMb, 1000, null, "{}",
    )

    private fun event(ts: Long, signal: DeviceSignal) = DeviceEvent(d1, ts, "x", "{}", signal)

    private suspend fun stored() = w.tx.transactional<List<Alert>, Nothing> {
        Outcome.Success(w.alerts.active(listOf(d1)))
    }.let { (it as Outcome.Success).value }

    @Test
    fun lowStorageTelemetryOpensR06AndPublishesAfterCommit() = runTest {
        ingest(DeviceDataBatch(telemetry = listOf(telemetry(10, freeMb = 400))))

        val alert = stored().single()
        assertThat(alert.rule).isEqualTo(RuleId.R06)
        assertThat(alert.restaurantId).isEqualTo(RestaurantId("r-1"))
        assertThat(alert.details).containsExactly("freeMb", "400", "limit", "500")
        val published = w.bus.published.filterIsInstance<RealtimeEvent.AlertChanged>().single()
        assertThat(published.view.alert).isEqualTo(alert)
        assertThat(published.view.restaurantCode).isEqualTo("MSK-01")
        assertThat(w.bus.published.filterIsInstance<RealtimeEvent.TelemetryReceived>()).hasSize(1)
    }

    @Test
    fun redeliveredBatchCreatesNoDuplicates() = runTest {
        val batch = DeviceDataBatch(
            telemetry = listOf(telemetry(10, freeMb = 400)),
            events = listOf(event(11, DeviceSignal.Threshold(DeviceSignal.TEMP_HIGH, true, 46.0, 45.0))),
        )
        ingest(batch)
        ingest(batch)

        assertThat(stored().map { it.rule }).containsExactly(RuleId.R06, RuleId.R03)
        assertThat(w.db.state.alerts).hasSize(2)
    }

    @Test
    fun enterAndExitInOneBatchOpensAndResolves() = runTest {
        ingest(DeviceDataBatch(telemetry = listOf(telemetry(20, freeMb = 700), telemetry(10, freeMb = 400))))

        val alert = w.db.state.alerts.values.single()
        assertThat(alert.state).isEqualTo(AlertState.RESOLVED)
        assertThat(alert.auto).isTrue()
        assertThat(alert.resolvedAt).isEqualTo(20)
        assertThat(w.db.state.conditions).isEmpty()
    }

    @Test
    fun failedCommitWritesNothingAndPublishesNothing() = runTest {
        w.tx.failNextCommit = true
        assertFailsWith<IllegalStateException> { ingest(DeviceDataBatch(telemetry = listOf(telemetry(10, 400)))) }

        assertThat(w.db.state.alerts).isEmpty()
        assertThat(w.db.state.conditions).isEmpty()
        assertThat(w.bus.published).isEmpty()
    }

    @Test
    fun decommissionedDeviceDoesNotRaiseAlerts() = runTest {
        w.device("d-1", status = DeviceStatus.DECOMMISSIONED)
        val processor = w.alertProcessor()
        val changed = w.tx.transactional<List<String>, Nothing> {
            Outcome.Success(processor.process(listOf(RuleInput.Telemetry(d1, 1, null, 10))))
        }
        assertThat((changed as Outcome.Success).value).isEmpty()
        assertThat(w.db.state.alerts).isEmpty()
    }

    @Test
    fun probeStateEventsAndResultsFeedTheAggregatorTable() = runTest {
        val result = ProbeResult("api", false, null, "timeout", 5000, null, null, null, null)
        val report = ProbeReport(d1, 30, listOf(result))
        ingest(
            DeviceDataBatch(
                probes = listOf(report),
                events = listOf(event(31, DeviceSignal.ProbeState("api", true, "timeout", 3))),
            ),
        )

        assertThat(w.db.state.probeStates[d1 to "api"])
            .isEqualTo(StoredProbeState(down = true, since = 31, lastResultAt = 30, error = "timeout"))
        assertThat(stored().single().subject).isEqualTo(AlertSubject(RuleId.R05, d1, "api"))
        ingest(DeviceDataBatch(probes = listOf(report.copy(ts = 60))))
        assertThat(w.db.state.probeStates.getValue(d1 to "api").lastResultAt).isEqualTo(60)
    }

    @Test
    fun offlineStatusOpensR01AndOnlineResolvesIt() = runTest {
        w.clock.now = 100
        status(StatusReport(d1, PresenceState.ONLINE, 1, "c-1"))
        w.clock.now = 200
        status(StatusReport(d1, PresenceState.OFFLINE, 2, "c-1", reason = "lwt"))

        val offline = stored().single()
        assertThat(offline.rule).isEqualTo(RuleId.R01)
        assertThat(offline.details).containsExactly("reason", "lwt")
        val statusEvents = w.bus.published.filterIsInstance<RealtimeEvent.DeviceStatusChanged>()
        assertThat(statusEvents.map { it.status }).containsExactly(DeviceStatus.ONLINE, DeviceStatus.OFFLINE).inOrder()
        assertThat(statusEvents.last().reason).isEqualTo("lwt")

        w.clock.now = 300
        status(StatusReport(d1, PresenceState.ONLINE, 3, "c-2"))
        assertThat(stored()).isEmpty()
        assertThat(w.db.state.alerts.values.single().auto).isTrue()
    }

    /** RV-5: автозакрытие не затирает подтверждение, комментарий и родителя, записанные после чтения тревог. */
    @Test
    fun autoResolveKeepsAcknowledgementAndParentCommittedMeanwhile() = runTest {
        ingest(DeviceDataBatch(telemetry = listOf(telemetry(10, freeMb = 400))))
        val id = stored().single().id
        w.alerts.afterActiveRead = {
            w.seed { s ->
                val acked = s.alerts.getValue(id).copy(
                    state = AlertState.ACKNOWLEDGED, ackBy = "support", ackAt = 15, comment = "чистим",
                    parentIncidentId = "i-1",
                )
                s.copy(alerts = s.alerts + (id to acked))
            }
        }

        ingest(DeviceDataBatch(telemetry = listOf(telemetry(20, freeMb = 700))))

        val alert = w.db.state.alerts.getValue(id)
        assertThat(alert.state).isEqualTo(AlertState.RESOLVED)
        assertThat(alert.auto).isTrue()
        assertThat(alert.resolvedAt).isEqualTo(20)
        assertThat(alert.ackBy).isEqualTo("support")
        assertThat(alert.comment).isEqualTo("чистим")
        assertThat(alert.parentIncidentId).isEqualTo("i-1")
    }

    /** RV-5: ручное закрытие, закоммиченное после чтения, автозакрытие не переписывает. */
    @Test
    fun autoResolveDoesNotOverwriteAManualResolveCommittedMeanwhile() = runTest {
        ingest(DeviceDataBatch(telemetry = listOf(telemetry(10, freeMb = 400))))
        val id = stored().single().id
        w.alerts.afterActiveRead = {
            w.seed { s ->
                val closed = s.alerts.getValue(id).copy(
                    state = AlertState.RESOLVED, resolvedAt = 15, resolvedBy = "support", comment = "очищено вручную",
                )
                s.copy(alerts = s.alerts + (id to closed))
            }
        }

        ingest(DeviceDataBatch(telemetry = listOf(telemetry(20, freeMb = 700))))

        val alert = w.db.state.alerts.getValue(id)
        assertThat(alert.auto).isFalse()
        assertThat(alert.resolvedAt).isEqualTo(15)
        assertThat(alert.resolvedBy).isEqualTo("support")
    }

    @Test
    fun staleOfflineOfForeignConnectionRaisesNothing() = runTest {
        status(StatusReport(d1, PresenceState.ONLINE, 1, "c-new"))
        status(StatusReport(d1, PresenceState.OFFLINE, 2, "c-old", reason = "lwt"))

        assertThat(w.db.state.alerts).isEmpty()
    }

    @Test
    fun acknowledgeThenResolveWithComment() = runTest {
        ingest(DeviceDataBatch(telemetry = listOf(telemetry(10, freeMb = 400))))
        val id = stored().single().id
        w.clock.now = 5_000

        val acked = (ack(id, "support", "  смотрим ") as Outcome.Success).value.alert
        assertThat(acked.state).isEqualTo(AlertState.ACKNOWLEDGED)
        assertThat(acked.ackBy).isEqualTo("support")
        assertThat(acked.ackAt).isEqualTo(5_000)
        assertThat(acked.comment).isEqualTo("смотрим")
        assertThat(w.alerts.lockedFor).contains(id)

        w.clock.now = 6_000
        val resolved = (resolve(id, "support", "почистили память") as Outcome.Success).value.alert
        assertThat(resolved.state).isEqualTo(AlertState.RESOLVED)
        assertThat(resolved.resolvedBy).isEqualTo("support")
        assertThat(resolved.resolvedAt).isEqualTo(6_000)
        assertThat(resolved.auto).isFalse()
        assertThat(w.bus.published.filterIsInstance<RealtimeEvent.AlertChanged>().map { it.view.alert.state })
            .containsExactly(AlertState.OPEN, AlertState.ACKNOWLEDGED, AlertState.RESOLVED).inOrder()
    }

    @Test
    fun lifecycleErrors() = runTest {
        ingest(DeviceDataBatch(telemetry = listOf(telemetry(10, freeMb = 400))))
        val id = stored().single().id

        val required = Outcome.Failure(ServerError.Validation("comment", "required"))
        assertThat(resolve(id, "support", null)).isEqualTo(required)
        assertThat(resolve(id, "support", "   ")).isEqualTo(required)
        assertThat(resolve(id, "support", "x".repeat(501)))
            .isEqualTo(Outcome.Failure(ServerError.Validation("comment", "too_long")))
        assertThat(ack(id, "support", "x".repeat(501)))
            .isEqualTo(Outcome.Failure(ServerError.Validation("comment", "too_long")))
        assertThat(resolve("nope", "support", "ok")).isEqualTo(Outcome.Failure(ServerError.NotFound("alert", "nope")))
        assertThat(ack(id, "support", null)).isInstanceOf(Outcome.Success::class.java)
        assertThat(ack(id, "support", null)).isEqualTo(Outcome.Failure(ServerError.Conflict("already_acknowledged")))
        assertThat(resolve(id, "support", "x".repeat(500))).isInstanceOf(Outcome.Success::class.java)
        assertThat(resolve(id, "support", "again")).isEqualTo(Outcome.Failure(ServerError.Conflict("already_resolved")))
        assertThat(ack(id, "support", null)).isEqualTo(Outcome.Failure(ServerError.Conflict("already_resolved")))
    }

    @Test
    fun manualResolveWhileConditionHoldsDoesNotReopen() = runTest {
        ingest(DeviceDataBatch(telemetry = listOf(telemetry(10, freeMb = 400))))
        resolve(stored().single().id, "support", "знаем")

        ingest(DeviceDataBatch(telemetry = listOf(telemetry(20, freeMb = 300))))
        assertThat(stored()).isEmpty()

        ingest(DeviceDataBatch(telemetry = listOf(telemetry(30, freeMb = 650), telemetry(40, freeMb = 450))))
        assertThat(stored().single().openedAt).isEqualTo(40)
    }

    @Test
    fun listFiltersAndHidesSuppressed() = runTest {
        ingest(
            DeviceDataBatch(
                telemetry = listOf(telemetry(10, freeMb = 400)),
                events = listOf(event(20, DeviceSignal.ProbeState("api", true, "timeout", 3))),
            ),
        )
        val r05 = stored().first { it.rule == RuleId.R05 }
        w.seed { it.copy(alerts = it.alerts + (r05.id to r05.copy(parentIncidentId = "i-1"))) }

        assertThat(list(AlertFilter()).map { it.alert.rule }).containsExactly(RuleId.R06)
        assertThat(list(AlertFilter(includeSuppressed = true)).map { it.alert.rule })
            .containsExactly(RuleId.R05, RuleId.R06).inOrder()
        assertThat(list(AlertFilter(rule = RuleId.R05))).isEmpty()
        assertThat(list(AlertFilter(state = AlertState.RESOLVED))).isEmpty()
        val second = list(AlertFilter(includeSuppressed = true), PageRequest.of(limit = 1, offset = 1))
        assertThat(second.single().alert.rule).isEqualTo(RuleId.R06)
    }

    @Test
    fun ruleCodesAndOrder() {
        assertThat(RuleId.fromCode("R-05")).isEqualTo(RuleId.R05)
        assertThat(RuleId.fromCode("R05")).isNull()
        assertThat(RuleId.ALERT_RULES.map { it.code }).containsExactly("R-01", "R-02", "R-03", "R-04", "R-05", "R-06")
        assertThat(AlertState.fromCode("acknowledged")).isEqualTo(AlertState.ACKNOWLEDGED)
        assertThat(AlertState.fromCode("x")).isNull()
        val base = Alert("a", AlertSubject(RuleId.R01, d1), RestaurantId("r"), AlertState.OPEN, openedAt = 5)
        val critical = base.copy(id = "b", subject = AlertSubject(RuleId.R03, d1), openedAt = 1)
        val newer = base.copy(id = "c", openedAt = 9)
        assertThat(listOf(base, critical, newer).sortedWith(activeOrder).map { it.id }).containsExactly("b", "c", "a")
            .inOrder()
    }
}
