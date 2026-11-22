package ru.restofleet.server.data.ingest

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.restofleet.server.data.db.ExposedAlertConditionRepository
import ru.restofleet.server.data.db.ExposedAlertRepository
import ru.restofleet.server.data.db.ExposedCommandRepository
import ru.restofleet.server.data.db.ExposedProbeStateRepository
import ru.restofleet.server.data.db.ExposedSessionRepository
import ru.restofleet.server.data.db.ExposedShadowRepository
import ru.restofleet.server.data.db.ExposedTelemetryRepository
import ru.restofleet.server.data.db.PostgresTest
import ru.restofleet.server.data.realtime.SharedFlowEventBus
import ru.restofleet.server.data.support.FakeBroker
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.Samples.T0
import ru.restofleet.server.data.support.TestClock
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.server.domain.alert.AlertProcessor
import ru.restofleet.server.domain.alert.AlertState
import ru.restofleet.server.domain.alert.RuleId
import ru.restofleet.server.domain.alert.RuleParams
import ru.restofleet.server.domain.command.CommandStatus
import ru.restofleet.server.domain.command.usecase.HandleCommandResult
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.ingest.usecase.IngestBatch
import ru.restofleet.server.domain.presence.usecase.HandleStatus
import ru.restofleet.server.domain.realtime.EventBus
import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.server.domain.shadow.Shadow
import ru.restofleet.server.domain.shadow.usecase.HandleReported
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.UuidIdGenerator
import ru.restofleet.shared.protocol.DeviceChannel
import ru.restofleet.shared.protocol.Topics
import java.io.File

/** Разбор пачки и запись через use cases в PostgreSQL; повтор пачки (передоставка) безопасен. */
internal class IngestProcessorOnPostgresTest : PostgresTest() {
    private val clock = TestClock()
    private val broker = FakeBroker()
    private val metrics = IngestMetrics()
    private val sessions = ExposedSessionRepository()
    private val shadows = ExposedShadowRepository()
    private val telemetry = ExposedTelemetryRepository()
    private val commands = ExposedCommandRepository()
    private val alerts = ExposedAlertRepository()
    private val conditions = ExposedAlertConditionRepository()
    private val bus = SharedFlowEventBus()
    private val published = mutableListOf<RealtimeEvent>()
    private val recordingBus = object : EventBus by bus {
        override fun publish(event: RealtimeEvent) {
            published += event
            bus.publish(event)
        }
    }
    private fun alertProcessor() = AlertProcessor(alerts, conditions, devices, UuidIdGenerator(), RuleParams())
    private val processor = IngestProcessor(
        DeviceMessageDecoder(64 * 1024),
        HandleStatus(
            devices, sessions, shadows, broker, alertProcessor(), alerts, recordingBus, TestDatabase.tx, clock,
        ),
        IngestBatch(
            devices, telemetry, alertProcessor(), alerts, ExposedProbeStateRepository(), recordingBus, TestDatabase.tx,
            clock,
        ),
        HandleReported(devices, shadows, TestDatabase.tx, clock),
        HandleCommandResult(commands, TestDatabase.tx, clock),
        metrics,
    )

    private fun fixture(name: String) = File(System.getProperty("fixturesDir"), "$name.json").readText()

    private fun msg(id: DeviceId, channel: DeviceChannel, text: String) =
        InboundMessage(Topics.of(id, channel), text.toByteArray()) {}

    @Test
    fun `one batch of every channel lands in its table and a redelivered batch adds only events`() {
        val d = givenDevice()
        val unknown = Samples.deviceId()
        tx { shadows.insert(Shadow.initial(d.id, DefaultDesired.document, T0)) }
        val command = Samples.command(d.id, cmdId = "c-01J9Z5")
        tx { commands.insert(command) }
        val batch = listOf(
            msg(d.id, DeviceChannel.STATUS, fixture("status-online")),
            msg(d.id, DeviceChannel.TELEMETRY, fixture("telemetry-kitchen")),
            msg(d.id, DeviceChannel.TELEMETRY, fixture("telemetry-kitchen")),
            msg(d.id, DeviceChannel.PROBE, fixture("probe-report")),
            msg(d.id, DeviceChannel.EVENTS, fixture("event-probe-state")),
            msg(d.id, DeviceChannel.EVENTS, fixture("event-debug-seq")),
            msg(d.id, DeviceChannel.SHADOW_REPORTED, fixture("shadow-reported")),
            msg(d.id, DeviceChannel.CMD_RES, fixture("cmd-res-succeeded")),
            msg(unknown, DeviceChannel.TELEMETRY, fixture("telemetry-kitchen")),
            msg(d.id, DeviceChannel.TELEMETRY, "{broken"),
        )

        runBlocking { processor.process(batch) }

        val device = tx { devices.findById(d.id) }!!
        assertThat(device.status).isEqualTo(DeviceStatus.ONLINE)
        assertThat(device.agentVersion).isEqualTo("0.1.0-mvp")
        assertThat(tx { sessions.latest(d.id) }!!.connId).isEqualTo("k-7f3a9c")
        assertThat(TestDatabase.scalar("SELECT count(*) FROM telemetry")).isEqualTo("1")
        assertThat(TestDatabase.scalar("SELECT count(*) FROM probe_results")).isEqualTo("3")
        assertThat(TestDatabase.scalar("SELECT count(*) FROM events")).isEqualTo("1")
        assertThat(tx { shadows.find(d.id) }!!.reportedVersion).isEqualTo(12)
        assertThat(tx { commands.find(command.cmdId) }!!.status).isEqualTo(CommandStatus.SUCCEEDED)
        assertThat(metrics.undecodable.get()).isEqualTo(1)
        assertThat(metrics.ignored.get()).isEqualTo(1)
        assertThat(metrics.dropped.get()).isEqualTo(1)
        // Desired v1 не подтверждён устройством на момент online — опубликован снова (реш. 233)
        assertThat(broker.calls).containsExactly("desired ${d.id.value} v1")

        runBlocking { processor.process(batch) }

        assertThat(TestDatabase.scalar("SELECT count(*) FROM telemetry")).isEqualTo("1")
        assertThat(TestDatabase.scalar("SELECT count(*) FROM probe_results")).isEqualTo("3")
        // У событий нет идентификатора: дубль при передоставке допустим (реш. 207)
        assertThat(TestDatabase.scalar("SELECT count(*) FROM events")).isEqualTo("2")
        assertThat(tx { sessions.latest(d.id) }!!.isOpen).isTrue()
    }

    @Test
    fun `low storage telemetry raises R-06 in the database and publishes it after commit, redelivery adds nothing`() {
        val d = givenDevice()
        val low = """{ "ts": ${T0 + 1}, "seq": 1, "storage": { "freeMb": 400 } }"""
        val batch = listOf(
            msg(d.id, DeviceChannel.TELEMETRY, low),
            msg(d.id, DeviceChannel.EVENTS, fixture("event-threshold-enter")),
            msg(d.id, DeviceChannel.EVENTS, fixture("event-probe-state")),
        )

        runBlocking { processor.process(batch) }
        runBlocking { processor.process(batch) }

        val active = tx { alerts.active(listOf(d.id)) }
        assertThat(active.map { it.rule }).containsExactly(RuleId.R06, RuleId.R02, RuleId.R05)
        assertThat(active.first { it.rule == RuleId.R06 }.details).containsExactly("freeMb", "400", "limit", "500")
        assertThat(active.first { it.rule == RuleId.R05 }.subject.target).isEqualTo("api")
        assertThat(active.map { it.state }.toSet()).containsExactly(AlertState.OPEN)
        val changed = published.filterIsInstance<RealtimeEvent.AlertChanged>()
        assertThat(changed.map { it.view.alert.rule }).containsExactly(RuleId.R06, RuleId.R02, RuleId.R05)
        assertThat(changed.first().view.restaurantCode).isEqualTo(tx { restaurants.findById(d.restaurantId) }!!.code)
        assertThat(published.filterIsInstance<RealtimeEvent.TelemetryReceived>()).hasSize(2)
    }

    @Test
    fun `offline status raises R-01 and online resolves it automatically`() {
        val d = givenDevice()
        runBlocking { processor.process(listOf(msg(d.id, DeviceChannel.STATUS, fixture("status-online")))) }
        runBlocking { processor.process(listOf(msg(d.id, DeviceChannel.STATUS, fixture("status-offline-lwt")))) }
        assertThat(tx { alerts.active(listOf(d.id)) }.single().rule).isEqualTo(RuleId.R01)

        val reconnect = fixture("status-online").replace("k-7f3a9c", "k-2")
        runBlocking { processor.process(listOf(msg(d.id, DeviceChannel.STATUS, reconnect))) }
        assertThat(tx { alerts.active(listOf(d.id)) }).isEmpty()
        val statuses = published.filterIsInstance<RealtimeEvent.DeviceStatusChanged>().map { it.status }
        assertThat(statuses).containsExactly(DeviceStatus.ONLINE, DeviceStatus.OFFLINE, DeviceStatus.ONLINE).inOrder()
    }

    @Test
    fun `late lwt of a replaced connection does not take the device offline`() {
        val d = givenDevice()
        val online = """{"state":"online","ts":1,"connId":"new"}"""
        val oldOnline = """{"state":"online","ts":0,"connId":"old"}"""
        val oldLwt = """{"state":"offline","ts":2,"connId":"old","reason":"lwt"}"""

        runBlocking {
            processor.process(listOf(msg(d.id, DeviceChannel.STATUS, oldOnline)))
            clock.now = T0 + 1_000
            processor.process(listOf(msg(d.id, DeviceChannel.STATUS, online), msg(d.id, DeviceChannel.STATUS, oldLwt)))
        }

        assertThat(tx { devices.findById(d.id) }!!.status).isEqualTo(DeviceStatus.ONLINE)
        val oldReason = TestDatabase.scalar("SELECT reason FROM device_sessions WHERE conn_id = 'old'")
        assertThat(oldReason).isEqualTo("replaced")
        assertThat(metrics.ignored.get()).isEqualTo(1)
    }
}
