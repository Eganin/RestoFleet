package ru.restofleet.agent.data.lifecycle

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
    private val wakeLock = FakeCpuWakeLock()

    @Test
    fun loopTicksImmediatelyThenEveryInterval() = runTest {
        var ticks = 0
        val loop = TelemetryLoop(intervalMs = 30_000, tick = { ticks++ }, wakeLock = wakeLock)
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
            wakeLock = wakeLock,
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
        val loop = TelemetryLoop(intervalMs = 1_000, tick = { throw OutOfMemoryError("oom") }, wakeLock = wakeLock)
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
    fun strayCancellationFromTickDoesNotKillTheLoop() = runTest {
        var ticks = 0
        val errors = mutableListOf<Throwable>()
        val loop = TelemetryLoop(
            intervalMs = 1_000,
            tick = { if (++ticks == 1) throw CancellationException("stray") },
            onError = { errors += it },
            wakeLock = wakeLock,
        )
        val job = launch { loop.run() }

        advanceTimeBy(1_500)
        runCurrent()

        assertThat(ticks).isEqualTo(2)
        assertThat(job.isActive).isTrue()
        assertThat(errors).hasSize(1)
        job.cancel()
    }

    @Test
    fun outerCancellationStopsTheLoop() = runTest {
        var ticks = 0
        val errors = mutableListOf<Throwable>()
        val loop = TelemetryLoop(
            intervalMs = 1_000,
            tick = { ticks++; awaitCancellation() },
            onError = { errors += it },
            wakeLock = wakeLock,
        )
        val job = launch { loop.run() }
        runCurrent()

        job.cancel()
        runCurrent()

        assertThat(job.isCancelled).isTrue()
        assertThat(ticks).isEqualTo(1)
        assertThat(errors).isEmpty()
    }

    @Test
    fun outerCancellationDuringStrayCancellationStillStopsTheLoop() = runTest {
        var ticks = 0
        val errors = mutableListOf<Throwable>()
        lateinit var job: kotlinx.coroutines.Job
        val loop = TelemetryLoop(
            intervalMs = 1_000,
            tick = {
                ticks++
                job.cancel()
                throw CancellationException("cancelled by caller")
            },
            onError = { errors += it },
            wakeLock = wakeLock,
        )
        job = launch { loop.run() }
        advanceTimeBy(5_000)
        runCurrent()

        assertThat(ticks).isEqualTo(1)
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
        val cycle = cycleOf(TelemetryLoop(1_000, { ticks++ }, wakeLock = wakeLock), journal)

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
        val cycle = cycleOf(TelemetryLoop(1_000, { ticks++ }, wakeLock = wakeLock), journal)
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
        val cycle = cycleOf(TelemetryLoop(1_000, {}, wakeLock = wakeLock), journal)
        cycle.start("a")
        cycle.stop()

        assertThat(cycle.start("b")).isTrue()
        cycle.stop()
    }

    @Test
    fun stopWithoutStartDoesNotJournal() = runTest {
        val journal = LifecycleJournal(File(tmp.root, "j"), clock)
        cycleOf(TelemetryLoop(1_000, {}, wakeLock = wakeLock), journal).stop()

        assertThat(journal.entries()).isEmpty()
    }

    @Test
    fun cycleWorkRunsOnInjectedDispatcherNotOnCaller() = runTest {
        val journal = LifecycleJournal(File(tmp.root, "j"), clock)
        var ticks = 0
        val cycle = AgentCycle(
            TestScope(testScheduler),
            TelemetryLoop(1_000, { ticks++ }, wakeLock = wakeLock),
            journal,
            StandardTestDispatcher(testScheduler),
            wakeLock,
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

    @Test
    fun loopRenewsWakeLockOnEveryTick() = runTest {
        val loop = TelemetryLoop(intervalMs = 1_000, tick = {}, wakeLock = wakeLock)
        val job = launch { loop.run() }

        runCurrent()
        assertThat(wakeLock.events).containsExactly("hold(${TelemetryLoop.WAKE_LOCK_TIMEOUT_MS})")
        advanceTimeBy(2_000)
        runCurrent()

        assertThat(wakeLock.events).hasSize(3)
        assertThat(wakeLock.events.toSet()).containsExactly("hold(${TelemetryLoop.WAKE_LOCK_TIMEOUT_MS})")
        job.cancel()
    }

    @Test
    fun loopReleasesWakeLockWhenItIsCancelled() = runTest {
        val job = launch { TelemetryLoop(intervalMs = 1_000, tick = {}, wakeLock = wakeLock).run() }
        runCurrent()
        assertThat(wakeLock.isHeld).isTrue()

        job.cancel()
        runCurrent()

        assertThat(wakeLock.isHeld).isFalse()
    }

    @Test
    fun wakeLockTimeoutCoversSeveralIntervalsButIsFinite() {
        assertThat(TelemetryLoop.WAKE_LOCK_TIMEOUT_MS).isEqualTo(600_000L)
    }

    @Test
    fun cycleHoldsWakeLockFromStartAndReleasesItOnStop() = runTest {
        val journal = LifecycleJournal(File(tmp.root, "j"), clock)
        val cycle = cycleOf(TelemetryLoop(1_000, {}, wakeLock = wakeLock), journal)

        cycle.start("boot")
        // удержан синхронно в start(), до того как корутина цикла получила управление
        assertThat(wakeLock.isHeld).isTrue()
        runCurrent()
        cycle.stop()

        assertThat(wakeLock.isHeld).isFalse()
        assertThat(wakeLock.events.last()).isEqualTo("release")
    }

    @Test
    fun repeatedStartDoesNotTouchWakeLock() = runTest {
        val journal = LifecycleJournal(File(tmp.root, "j"), clock)
        val cycle = cycleOf(TelemetryLoop(1_000, {}, wakeLock = wakeLock), journal)
        cycle.start("boot")
        val before = wakeLock.events.size

        cycle.start("watchdog")

        assertThat(wakeLock.events).hasSize(before)
        cycle.stop()
    }

    @Test
    fun stopWithoutStartDoesNotReleaseWakeLock() = runTest {
        cycleOf(TelemetryLoop(1_000, {}, wakeLock = wakeLock), LifecycleJournal(File(tmp.root, "j"), clock)).stop()

        assertThat(wakeLock.events).isEmpty()
    }

    private fun kotlinx.coroutines.test.TestScope.advanceUntilIdleOnce() = runCurrent()

    private fun TestScope.cycleOf(loop: TelemetryLoop, journal: LifecycleJournal) =
        AgentCycle(TestScope(testScheduler), loop, journal, StandardTestDispatcher(testScheduler), wakeLock)
}
