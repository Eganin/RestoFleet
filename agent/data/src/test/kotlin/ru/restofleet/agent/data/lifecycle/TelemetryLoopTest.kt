package ru.restofleet.agent.data.lifecycle

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.restofleet.agent.domain.port.SequenceSource
import ru.restofleet.agent.domain.port.TelemetrySource
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.agent.domain.usecase.CollectTelemetry
import ru.restofleet.shared.kernel.Clock
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryLoopTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 0L
    private val clock = Clock { now }

    @Test
    fun loopTicksImmediatelyThenEveryInterval() = runTest {
        var ticks = 0
        val loop = TelemetryLoop(intervalMs = 30_000, tick = { ticks++ })
        val job = launch { loop.run() }

        runCurrent()
        assertThat(ticks).isEqualTo(1)
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(ticks).isEqualTo(2)
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(ticks).isEqualTo(4)
        job.cancel()
    }

    @Test
    fun failingTickDoesNotStopTheLoop() = runTest {
        var ticks = 0
        val errors = mutableListOf<String>()
        val loop = TelemetryLoop(
            intervalMs = 1_000,
            tick = {
                ticks++
                if (ticks == 1) error("boom")
            },
            onError = { errors += it.message.orEmpty() },
        )
        val job = launch { loop.run() }

        advanceTimeBy(2_500)
        runCurrent()

        assertThat(ticks).isEqualTo(3)
        assertThat(errors).containsExactly("boom")
        job.cancel()
    }

    @Test
    fun errorIsNotSwallowed() = runTest {
        val loop = TelemetryLoop(intervalMs = 1_000, tick = { throw OutOfMemoryError("oom") })
        var thrown: Throwable? = null
        val job = launch {
            try {
                loop.run()
            } catch (t: Throwable) {
                thrown = t
            }
        }
        runCurrent()

        assertThat(thrown).isInstanceOf(OutOfMemoryError::class.java)
        job.cancel()
    }

    @Test
    fun cancellationInsideTickIsPropagatedNotReported() = runTest {
        val errors = mutableListOf<Throwable>()
        val loop = TelemetryLoop(
            intervalMs = 1_000,
            tick = { throw CancellationException("stop") },
            onError = { errors += it },
        )
        val job = launch { loop.run() }
        runCurrent()

        assertThat(job.isCancelled).isTrue()
        assertThat(errors).isEmpty()
    }

    private class EmptySource : TelemetrySource {
        private fun <T> unavailable(): Reading<T> = Reading.Unavailable(UnavailableReason.NoSensor)
        override suspend fun battery() = unavailable<ru.restofleet.agent.domain.model.BatteryState>()
        override suspend fun thermal() = unavailable<ru.restofleet.agent.domain.model.ThermalState>()
        override suspend fun network() = unavailable<ru.restofleet.agent.domain.model.NetworkState>()
        override suspend fun resources() = unavailable<ru.restofleet.agent.domain.model.DeviceResources>()
        override suspend fun foregroundApp() = unavailable<ru.restofleet.agent.domain.model.ForegroundApp>()
        override suspend fun apps(watched: List<String>) =
            unavailable<List<ru.restofleet.agent.domain.model.AppVersion>>()
    }

    private val collect = CollectTelemetry(EmptySource(), clock, SequenceSource { 7L })

    @Test
    fun tickCollectsLogsThenAppendsHeartbeat() = runTest {
        val heartbeat = HeartbeatLog(File(tmp.root, "hb"), clock)
        val logged = mutableListOf<String>()
        val tick = TelemetryTick(collect, heartbeat, listOf("pkg.a"), logged::add)
        now = 5_000

        tick()

        assertThat(heartbeat.timestamps()).containsExactly(5_000L)
        assertThat(logged.single()).contains("seq=7")
    }

    @Test
    fun tickDoesNotAppendHeartbeatWhenCollectFails() = runTest {
        val heartbeat = HeartbeatLog(File(tmp.root, "hb"), clock)
        val failing = CollectTelemetry(
            object : TelemetrySource by EmptySource() {
                override suspend fun battery(): Reading<ru.restofleet.agent.domain.model.BatteryState> =
                    throw OutOfMemoryError()
            },
            clock,
            SequenceSource { 1L },
        )
        val tick = TelemetryTick(failing, heartbeat, emptyList()) {}

        runCatching { tick() }

        assertThat(heartbeat.timestamps()).isEmpty()
    }

    @Test
    fun cycleStartsOnceAndJournalsRealStartsOnly() = runTest {
        val journal = LifecycleJournal(File(tmp.root, "j"), clock)
        var ticks = 0
        val cycle = cycleOf(TelemetryLoop(1_000, { ticks++ }), journal)

        now = 10
        assertThat(cycle.start("boot")).isTrue()
        now = 20
        assertThat(cycle.start("watchdog")).isFalse()
        runCurrent()

        assertThat(ticks).isEqualTo(1)
        assertThat(journal.entries().map { it.kind to it.reason })
            .containsExactly(LifecycleEntry.Kind.START to "boot")
        assertThat(cycle.isRunning).isTrue()
        cycle.stop() // иначе runTest зациклится на бесконечном цикле в виртуальном времени
    }

    @Test
    fun cycleStopCancelsLoopAndJournalsStop() = runTest {
        val journal = LifecycleJournal(File(tmp.root, "j"), clock)
        var ticks = 0
        val cycle = cycleOf(TelemetryLoop(1_000, { ticks++ }), journal)
        cycle.start("manual")
        runCurrent()

        now = 99
        cycle.stop()
        advanceTimeBy(10_000)
        runCurrent()

        assertThat(ticks).isEqualTo(1)
        assertThat(cycle.isRunning).isFalse()
        assertThat(journal.entries().last()).isEqualTo(LifecycleEntry(LifecycleEntry.Kind.STOP, 99, null))
    }

    @Test
    fun cycleCanRestartAfterStop() = runTest {
        val journal = LifecycleJournal(File(tmp.root, "j"), clock)
        val cycle = cycleOf(TelemetryLoop(1_000, {}), journal)
        cycle.start("a")
        cycle.stop()

        assertThat(cycle.start("b")).isTrue()
        cycle.stop()
    }

    @Test
    fun stopWithoutStartDoesNotJournal() = runTest {
        val journal = LifecycleJournal(File(tmp.root, "j"), clock)
        cycleOf(TelemetryLoop(1_000, {}), journal).stop()

        assertThat(journal.entries()).isEmpty()
    }

    @Test
    fun cycleWorkRunsOnInjectedDispatcherNotOnCaller() = runTest {
        val journal = LifecycleJournal(File(tmp.root, "j"), clock)
        var ticks = 0
        val cycle = AgentCycle(
            TestScope(testScheduler),
            TelemetryLoop(1_000, { ticks++ }),
            journal,
            StandardTestDispatcher(testScheduler),
        )

        assertThat(cycle.start("boot")).isTrue()
        // start() вернулся, но ни тик, ни запись журнала на вызывающем потоке не выполнены
        assertThat(ticks).isEqualTo(0)
        assertThat(journal.entries()).isEmpty()

        advanceUntilIdleOnce()
        assertThat(ticks).isEqualTo(1)
        assertThat(journal.entries().single().reason).isEqualTo("boot")
        cycle.stop()
    }

    private fun kotlinx.coroutines.test.TestScope.advanceUntilIdleOnce() = runCurrent()

    private fun TestScope.cycleOf(loop: TelemetryLoop, journal: LifecycleJournal) =
        AgentCycle(TestScope(testScheduler), loop, journal, StandardTestDispatcher(testScheduler))
}
