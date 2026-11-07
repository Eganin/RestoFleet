package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import ru.restofleet.agent.domain.fake.FakeTelemetrySource
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.agent.domain.port.SequenceSource
import ru.restofleet.shared.kernel.Clock
import java.util.concurrent.CancellationException
import kotlin.test.assertFailsWith

class CollectTelemetryTest {

    private val source = FakeTelemetrySource()
    private var counter = 0L
    private val collect = CollectTelemetry(source, Clock { 1_700_000_000_000L }, SequenceSource { ++counter })

    @Test
    fun allReadingsAvailable() = runTest {
        val snapshot = collect(listOf("ru.restofleet.kiosk"))

        assertThat(snapshot.ts).isEqualTo(1_700_000_000_000L)
        assertThat(snapshot.seq).isEqualTo(1L)
        assertThat(snapshot.battery).isEqualTo(FakeTelemetrySource.BATTERY)
        assertThat(snapshot.thermal).isEqualTo(FakeTelemetrySource.THERMAL)
        assertThat(snapshot.network).isEqualTo(FakeTelemetrySource.NETWORK)
        assertThat(snapshot.resources).isEqualTo(FakeTelemetrySource.RESOURCES)
        assertThat(snapshot.foregroundApp).isEqualTo(FakeTelemetrySource.FOREGROUND)
        assertThat(snapshot.apps).isEqualTo(FakeTelemetrySource.APPS)
        assertThat(snapshot.unavailable).isEmpty()
    }

    @Test
    fun watchedAppsArePassedToSource() = runTest {
        var received: List<String>? = null
        source.appsResult = { received = it; Reading.Available(emptyList()) }

        collect(listOf("a", "b"))

        assertThat(received).containsExactly("a", "b").inOrder()
    }

    @Test
    fun thermalUnavailableOnApi26() = runTest {
        val reason = UnavailableReason.ApiLevelTooLow(29, 26)
        source.thermalResult = { Reading.Unavailable(reason) }

        val snapshot = collect(emptyList())

        assertThat(snapshot.thermal).isNull()
        assertThat(snapshot.unavailable["thermal"]).isEqualTo(reason)
        assertThat(snapshot.battery).isNotNull()
    }

    @Test
    fun everyReadingReportsUnderItsOwnKey() = runTest {
        val reason = UnavailableReason.NoSensor
        source.batteryResult = { Reading.Unavailable(reason) }
        source.networkResult = { Reading.Unavailable(reason) }
        source.resourcesResult = { Reading.Unavailable(reason) }
        source.foregroundResult = { Reading.Unavailable(reason) }
        source.appsResult = { Reading.Unavailable(reason) }

        val snapshot = collect(emptyList())

        assertThat(snapshot.unavailable.keys)
            .containsExactly("battery", "network", "resources", "foregroundApp", "apps")
        assertThat(snapshot.apps).isEmpty()
        assertThat(snapshot.thermal).isNotNull()
    }

    @Test
    fun throwingCollectorIsIsolated() = runTest {
        source.batteryResult = { throw SecurityException("denied") }

        val snapshot = collect(emptyList())

        assertThat(snapshot.battery).isNull()
        assertThat(snapshot.unavailable["battery"]).isEqualTo(UnavailableReason.Failed("denied"))
        assertThat(snapshot.network).isEqualTo(FakeTelemetrySource.NETWORK)
    }

    @Test
    fun throwableWithoutMessageUsesClassName() = runTest {
        source.networkResult = { throw IllegalStateException() }

        val snapshot = collect(emptyList())

        assertThat(snapshot.unavailable["network"]).isEqualTo(UnavailableReason.Failed("IllegalStateException"))
    }

    @Test
    fun throwingAppsYieldsEmptyListAndUnavailable() = runTest {
        source.appsResult = { error("pm broken") }

        val snapshot = collect(listOf("x"))

        assertThat(snapshot.apps).isEmpty()
        assertThat(snapshot.unavailable["apps"]).isEqualTo(UnavailableReason.Failed("pm broken"))
        assertThat(snapshot.resources).isNotNull()
    }

    @Test
    fun strayCancellationFromReadingIsAFailureNotAnAbort() = runTest {
        source.batteryResult = { throw CancellationException("stray") }

        val snapshot = collect(emptyList())

        assertThat(snapshot.battery).isNull()
        assertThat(snapshot.unavailable["battery"]).isEqualTo(UnavailableReason.Failed("stray"))
        assertThat(snapshot.network).isEqualTo(FakeTelemetrySource.NETWORK)
    }

    @Test
    fun innerTimeoutOfOneReadingDoesNotAbortSnapshot() = runTest {
        source.batteryResult = { withTimeout(10) { awaitCancellation() } }

        val snapshot = collect(emptyList())

        assertThat(snapshot.battery).isNull()
        assertThat(snapshot.unavailable).containsKey("battery")
        assertThat(snapshot.network).isEqualTo(FakeTelemetrySource.NETWORK)
    }

    @Test
    fun outerCancellationStillPropagates() = runTest {
        val started = CompletableDeferred<Unit>()
        source.batteryResult = { started.complete(Unit); awaitCancellation() }

        val job = async(start = CoroutineStart.UNDISPATCHED) { collect(emptyList()) }
        started.await()
        job.cancel()

        assertFailsWith<CancellationException> { job.await() }
    }

    @Test
    fun cancellationOfCallerInsideReadingStopsCollectionInInvokeItself() = runTest {
        var networkReads = 0
        lateinit var outer: Job
        source.batteryResult = {
            outer.cancel()
            throw CancellationException("cancelled by caller") // без suspend-точки: вернуть/бросить можно сразу
        }
        source.networkResult = { networkReads++; Reading.Available(FakeTelemetrySource.NETWORK) }
        var outcome: Result<*>? = null
        outer = launch { outcome = runCatching { collect(emptyList()) } }

        advanceUntilIdle()

        assertThat(outcome!!.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(networkReads).isEqualTo(0)
    }

    @Test
    fun hangingReadingTimesOutAndOthersAreCollected() = runTest {
        source.batteryResult = { awaitCancellation() }
        val timed = CollectTelemetry(source, Clock { 1L }, SequenceSource { 1L }, readingTimeoutMs = 5_000)

        val snapshot = timed(emptyList())

        assertThat(snapshot.unavailable["battery"]).isEqualTo(UnavailableReason.Failed("timeout 5000 ms"))
        assertThat(snapshot.network).isEqualTo(FakeTelemetrySource.NETWORK)
        assertThat(currentTime).isEqualTo(5_000L)
    }

    @Test
    fun everyHangingReadingGetsItsOwnTimeout() = runTest {
        source.batteryResult = { awaitCancellation() }
        source.thermalResult = { awaitCancellation() }
        val timed = CollectTelemetry(source, Clock { 1L }, SequenceSource { 1L }, readingTimeoutMs = 1_000)

        val snapshot = timed(emptyList())

        assertThat(snapshot.unavailable.keys).containsExactly("battery", "thermal")
        assertThat(currentTime).isEqualTo(2_000L)
    }

    @Test
    fun errorIsNotSwallowed() = runTest {
        source.batteryResult = { throw OutOfMemoryError("oom") }

        assertFailsWith<OutOfMemoryError> { collect(emptyList()) }
    }

    @Test
    fun seqIsMonotonic() = runTest {
        val seqs = List(3) { collect(emptyList()).seq }

        assertThat(seqs).containsExactly(1L, 2L, 3L).inOrder()
    }
}
