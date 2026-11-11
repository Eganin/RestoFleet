package ru.restofleet.server.simulator.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.simulator.domain.model.CommandLoad
import ru.restofleet.server.simulator.domain.model.DeviceCredentials
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.model.IncomingCommand
import ru.restofleet.server.simulator.domain.model.LossMode
import ru.restofleet.server.simulator.domain.model.LossTest
import ru.restofleet.server.simulator.domain.model.Scenario
import ru.restofleet.server.simulator.domain.model.TransportMode
import ru.restofleet.server.simulator.domain.port.Incoming
import ru.restofleet.server.simulator.domain.runtime.CommandReceipts
import ru.restofleet.server.simulator.domain.runtime.DeviceRunner
import ru.restofleet.server.simulator.domain.runtime.RunMetrics
import ru.restofleet.server.simulator.domain.usecase.RunExperiment
import ru.restofleet.server.simulator.support.FakeConnector
import ru.restofleet.server.simulator.support.FakeControl
import ru.restofleet.server.simulator.support.FakeProbes
import ru.restofleet.server.simulator.support.FakeSink
import ru.restofleet.server.simulator.support.FakeTraffic
import ru.restofleet.server.simulator.support.creds
import kotlin.test.assertFailsWith

/** План 4.2: разгон, окно, команды измерителя и их задержка, замер пропажи, остановка — в виртуальном времени. */
class RunExperimentTest {
    private val metrics = RunMetrics()
    private val receipts = CommandReceipts()
    private val sink = FakeSink()
    private val devices: List<DeviceCredentials> = (0 until 12).map { creds(it, DeviceRole.entries[it % 3]) }

    private fun TestScope.experiment(connector: FakeConnector, control: FakeControl): RunExperiment {
        val clock = { testScheduler.currentTime }
        return RunExperiment(
            connector, control, DeviceRunner(clock, FakeProbes(), metrics, receipts), metrics, receipts, FakeTraffic(),
            sink, clock, { testScheduler.currentTime * 1_000_000 },
        )
    }

    private fun scenario(mode: TransportMode, poll: Int? = null) = Scenario(
        name = "test-${mode.code}", mode = mode, devices = 10, rampPerSec = 5, warmupSec = 2, durationSec = 10,
        pollIntervalSec = poll, jitter = 0.0, commands = CommandLoad(perMinute = 60),
        loss = LossTest(devices = 2, mode = LossMode.SILENT, timeoutSec = 60),
    )

    @Test
    fun mqttRunMeasuresCommandLatencyAndLossDetection() = runTest {
        val connector = FakeConnector(pushes = true)
        val control = FakeControl { deviceId, cmdId ->
            backgroundScope.launch {
                delay(50)
                val now = testScheduler.currentTime
                connector.links.getValue(deviceId)
                    .push(Incoming.Command(IncomingCommand(cmdId, "ping", emptyMap(), null, now * 1_000_000)))
            }
        }
        // Бэкенд «видит» пропажу на 45-м опросе после неё (опрос — раз в секунду)
        val polls = HashMap<String, Int>()
        listOf("d-8", "d-9").forEach { id ->
            control.deviceStatus[id] = {
                val vanished = connector.links.getValue(id).vanished != null
                if (vanished && polls.merge(id, 1, Int::plus)!! >= 45) "offline" else "online"
            }
        }

        val report = experiment(connector, control)(scenario(TransportMode.MQTT), devices)

        assertThat(connector.links.keys).containsExactlyElementsIn((0 until 10).map { "d-$it" }).inOrder()
        assertThat(connector.victims).containsExactly("d-8", "d-9")
        assertThat(report.connectedAtWindowStart).isEqualTo(10)
        assertThat(report.windowEndMillis - report.windowStartMillis).isEqualTo(10_000)
        assertThat(report.rampEndMillis).isAtMost(2_000)
        assertThat(report.commands).hasSize(10)
        assertThat(report.commands.map { it.deviceId }.toSet()).containsNoneOf("d-8", "d-9")
        assertThat(report.commands.map { it.latencyMs }.toSet()).containsExactly(50.0)
        assertThat(report.commands.map { it.finalStatus }.toSet()).containsExactly("queued")
        assertThat(report.windowCounters.messagesOut).isEqualTo(0)
        assertThat(report.windowTraffic!!.rxBytes).isGreaterThan(0)

        assertThat(report.detections.map { it.deviceId }).containsExactly("d-8", "d-9")
        assertThat(report.detections.map { it.offlineAfterMs }).containsExactly(45_000L, 45_000L)
        assertThat(connector.links.getValue("d-8").vanished).isEqualTo(LossMode.SILENT)
        assertThat(connector.links.getValue("d-8").released).isTrue()
        assertThat(connector.links.getValue("d-0").closed).isTrue()
        assertThat(connector.links.getValue("d-8").closed).isFalse()
        assertThat(connector.shutdown).isTrue()
        assertThat(report.timeline.map { it.phase }.distinct()).containsAtLeast("ramp", "window", "grace", "stop")
        assertThat(sink.runs).containsExactly(report)
        assertThat(metrics.snapshot().connected).isEqualTo(0)
    }

    @Test
    fun restRunWaitsForTheNextPollAndRecordsUndelivered() = runTest {
        val connector = FakeConnector(pushes = false)
        val control = FakeControl { deviceId, cmdId ->
            if (deviceId != "d-3") {
                val now = testScheduler.currentTime
                connector.links.getValue(deviceId).pollQueue += IncomingCommand(cmdId, "ping", emptyMap(), null, 0)
                    .copy(receivedAtNanos = now * 1_000_000 + 4_000_000_000)
            }
        }
        val run = scenario(TransportMode.REST, poll = 5).copy(loss = null, devices = 4)

        val report = experiment(connector, control)(run, devices)

        val delivered = report.commands.filter { it.deviceId != "d-3" }
        assertThat(delivered.map { it.latencyMs }.toSet()).containsExactly(4_000.0)
        assertThat(report.commands.filter { it.deviceId == "d-3" }.all { it.latencyMs == null }).isTrue()
        assertThat(report.detections).isEmpty()
        assertThat(connector.links.values.all { it.polls > 0 && it.closed }).isTrue()
    }

    @Test
    fun failedCommandsAreCountedAndTooFewDevicesRejected() = runTest {
        val control = FakeControl().apply { failCommands = true }
        val report = experiment(FakeConnector(pushes = true), control)(
            scenario(TransportMode.MQTT).copy(loss = null, devices = 3), devices,
        )
        assertThat(report.commands).isEmpty()
        assertThat(report.commandSendFailures).isEqualTo(10)
        assertFailsWith<IllegalArgumentException> {
            experiment(FakeConnector(pushes = true), FakeControl())(scenario(TransportMode.MQTT), devices.take(5))
        }
    }
}
