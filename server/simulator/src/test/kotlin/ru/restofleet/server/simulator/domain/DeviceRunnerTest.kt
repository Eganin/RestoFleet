package ru.restofleet.server.simulator.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.simulator.domain.device.VirtualDevice
import ru.restofleet.server.simulator.domain.model.DesiredUpdate
import ru.restofleet.server.simulator.domain.model.DeviceConfig
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.model.IncomingCommand
import ru.restofleet.server.simulator.domain.model.ProbeTarget
import ru.restofleet.server.simulator.domain.model.ReplyStatus
import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.StatusInfo
import ru.restofleet.server.simulator.domain.model.Thresholds
import ru.restofleet.server.simulator.domain.port.Incoming
import ru.restofleet.server.simulator.domain.profile.DayClock
import ru.restofleet.server.simulator.domain.profile.HallProfile
import ru.restofleet.server.simulator.domain.runtime.CommandReceipts
import ru.restofleet.server.simulator.domain.runtime.DeviceRunner
import ru.restofleet.server.simulator.domain.runtime.DeviceTiming
import ru.restofleet.server.simulator.domain.runtime.RunMetrics
import ru.restofleet.server.simulator.support.FakeLink
import ru.restofleet.server.simulator.support.FakeProbes
import java.time.ZoneId
import kotlin.random.Random

/** Циклы устройства поверх транспорта: телеметрия, тень, команды (push и опрос), пробы — в виртуальном времени. */
class DeviceRunnerTest {
    private val status = StatusInfo(DeviceRole.HALL, "sim", "boot")
    private val metrics = RunMetrics()
    private val receipts = CommandReceipts()

    private fun TestScope.runner(probes: FakeProbes = FakeProbes()) =
        DeviceRunner({ testScheduler.currentTime }, probes, metrics, receipts)

    private fun TestScope.device(interval: Int = 30) = VirtualDevice(
        HallProfile, DayClock(ZoneId.of("UTC"), 1.0, 0), Random(1), testScheduler.currentTime,
        DeviceConfig(telemetryIntervalSec = interval),
    )

    private fun cmd(id: String, type: String = "ping", params: Map<String, String> = emptyMap()) =
        IncomingCommand(id, type, params, expiresAt = null, receivedAtNanos = 42)

    @Test
    fun mqttDeviceSendsTelemetryAppliesShadowAndAnswersCommands() = runTest {
        val link = FakeLink(pushesCommands = true)
        val job = launch { runner().run(device(), link, status, DeviceTiming(null, 0.0), seed = 3) }
        runCurrent()
        assertThat(link.opened).isEqualTo(status)
        assertThat(metrics.snapshot().connected).isEqualTo(1)

        advanceTimeBy(30_000)
        runCurrent()
        assertThat(link.telemetry).hasSize(1)
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(link.telemetry.map { it.seq }).containsExactly(1L, 2L, 3L).inOrder()

        link.push(Incoming.Desired(DesiredUpdate(5, 10, Thresholds(), emptyList(), emptyList())))
        link.push(Incoming.Command(cmd("c-1")))
        link.push(Incoming.Command(cmd("c-1")))
        runCurrent()
        assertThat(link.reported.single().appliedVersion).isEqualTo(5)
        assertThat(link.replies.map { it.status })
            .containsExactly(ReplyStatus.RECEIVED, ReplyStatus.SUCCEEDED).inOrder()
        assertThat(receipts.receivedAt("c-1")).isEqualTo(42)

        // Новый интервал из тени действует со следующего тика
        val before = link.telemetry.size
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(link.telemetry.size - before).isAtLeast(4)

        job.cancelAndJoin()
        assertThat(metrics.snapshot().connected).isEqualTo(0)
    }

    @Test
    fun restDevicePollsCommandsAndRepliesOnlyWithTheOutcome() = runTest {
        val link = FakeLink(pushesCommands = false)
        val timing = DeviceTiming(pollIntervalSec = 5, jitter = 0.1)
        val job = launch { runner().run(device(), link, status, timing, seed = 1) }
        advanceTimeBy(60_001)
        runCurrent()
        assertThat(link.polls).isIn(com.google.common.collect.Range.closed(10, 15))
        link.pollQueue += cmd("c-9")
        advanceTimeBy(6_000)
        runCurrent()
        assertThat(link.replies.map { it.cmdId to it.status }).containsExactly("c-9" to ReplyStatus.SUCCEEDED)
        assertThat(receipts.receivedAt("c-9")).isEqualTo(42)
        job.cancelAndJoin()
    }

    @Test
    fun failedConnectStopsTheDevice() = runTest {
        val link = FakeLink(pushesCommands = true, openResult = false)
        runner().run(device(), link, status, DeviceTiming(null, 0.0), seed = 1)
        assertThat(metrics.snapshot().connectFailures).isEqualTo(1)
        assertThat(metrics.snapshot().connected).isEqualTo(0)
    }

    @Test
    fun plannedProbesRunOnScheduleAndCommandsUseThem() = runTest {
        val probes = FakeProbes(ok = false)
        val link = FakeLink(pushesCommands = true)
        val target = ProbeTarget("api", "http", "https://x/health", intervalSec = 5, timeoutMs = 100, failThreshold = 1)
        val job = launch { runner(probes).run(device(), link, status, DeviceTiming(null, 0.0), seed = 1) }
        runCurrent()
        link.push(Incoming.Desired(DesiredUpdate(2, 30, Thresholds(), emptyList(), listOf(target))))
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(probes.runs).isNotEmpty()
        val down = link.events.filterIsInstance<SimEvent.ProbeState>().single()
        assertThat(down.state).isEqualTo("down")
        assertThat(link.probes).isNotEmpty()

        link.push(Incoming.Command(cmd("p-1", "run_probe", mapOf("target" to "api"))))
        link.push(Incoming.Command(cmd("d-1", "collect_diagnostics")))
        runCurrent()
        val outcomes = link.replies.filter { it.status != ReplyStatus.RECEIVED }.associateBy { it.cmdId }
        assertThat(outcomes.getValue("p-1").result).containsEntry("ok", "false")
        assertThat(outcomes.getValue("d-1").result).containsEntry("probesFailed", "api")
        job.cancelAndJoin()
    }

    @Test
    fun jitterKeepsPeriodWithinBounds() {
        val random = Random(1)
        repeat(100) {
            val period = DeviceRunner.jittered(1_000, 0.1, random)
            assertThat(period).isIn(com.google.common.collect.Range.closed(900L, 1_100L))
        }
        assertThat(DeviceRunner.jittered(1_000, 0.0, random)).isEqualTo(1_000)
    }
}
