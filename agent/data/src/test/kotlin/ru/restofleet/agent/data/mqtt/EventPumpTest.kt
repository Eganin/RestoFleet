package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.data.collectors.PackageChange
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.NetworkType
import ru.restofleet.agent.domain.port.WatchedApps
import ru.restofleet.agent.domain.usecase.PublishEvent
import ru.restofleet.agent.domain.usecase.PublishPackageChange
import ru.restofleet.shared.kernel.Clock

class EventPumpTest {
    private val events = mutableListOf<AgentEvent>()
    private val publishEvent = mockk<PublishEvent>()
    private val network = Channel<NetworkState>(Channel.UNLIMITED)
    private val packages = Channel<PackageChange>(Channel.UNLIMITED)
    private var packageSubscriptions = mutableListOf<Set<String>>()
    private var networkSubscriptions = 0

    init {
        coEvery { publishEvent(any()) } coAnswers { events += firstArg<AgentEvent>() }
    }

    private fun TestScope.pump(
        networkFlow: () -> Flow<NetworkState> = {
            networkSubscriptions++
            network.receiveAsFlow()
        },
        log: MutableList<String> = mutableListOf(),
    ) = EventPump(
        scope = backgroundScope,
        networkChanges = networkFlow,
        packageEvents = { watched ->
            packageSubscriptions += watched
            packages.receiveAsFlow()
        },
        watched = WatchedApps { setOf("com.android.chrome") },
        publishEvent = publishEvent,
        publishPackageChange = PublishPackageChange(publishEvent, Clock { 500 }),
        clock = Clock { 500 },
        retryDelayMs = 10_000,
        log = { log += it },
    )

    @Test
    fun networkChangeBecomesNetworkChangedEventStampedByTheClock() = runTest {
        pump().start()
        runCurrent()

        network.trySend(NetworkState(NetworkType.WIFI, -50, true))
        runCurrent()

        assertThat(events).containsExactly(AgentEvent.NetworkChanged(500, NetworkState(NetworkType.WIFI, -50, true)))
    }

    @Test
    fun duplicatePackageChangeIsSentOnce() = runTest {
        // Реш. 204: система присылает одно обновление дважды — событие одно
        pump().start()
        runCurrent()

        repeat(2) { packages.trySend(PackageChange("com.android.chrome", PackageChange.Change.REPLACED, 14)) }
        runCurrent()

        assertThat(events).hasSize(1)
    }

    @Test
    fun packageChangeBecomesPackageChangedEventForWatchedApps() = runTest {
        pump().start()
        runCurrent()

        packages.trySend(PackageChange("com.android.chrome", PackageChange.Change.REPLACED, 14))
        packages.trySend(PackageChange("com.android.chrome", PackageChange.Change.REMOVED, null))
        runCurrent()

        assertThat(packageSubscriptions).containsExactly(setOf("com.android.chrome"))
        assertThat(events).containsExactly(
            AgentEvent.PackageChanged(500, "com.android.chrome", AgentEvent.PackageChanged.Change.REPLACED, 14),
            AgentEvent.PackageChanged(500, "com.android.chrome", AgentEvent.PackageChanged.Change.REMOVED, null),
        ).inOrder()
    }

    @Test
    fun publishFailureOfOneEventDoesNotStopTheNextOnes() = runTest {
        var first = true
        coEvery { publishEvent(any()) } coAnswers {
            if (first) {
                first = false
                throw IllegalStateException("db")
            }
            events += firstArg<AgentEvent>()
        }
        pump().start()
        runCurrent()

        network.trySend(NetworkState(NetworkType.WIFI, null, true))
        network.trySend(NetworkState(NetworkType.CELLULAR, null, true))
        runCurrent()

        assertThat(events).hasSize(1)
        assertThat((events.single() as AgentEvent.NetworkChanged).state.type).isEqualTo(NetworkType.CELLULAR)
    }

    @Test
    fun failedSourceIsResubscribedAfterTheRetryDelay() = runTest {
        val log = mutableListOf<String>()
        var calls = 0
        val failing = {
            calls++
            if (calls == 1) {
                kotlinx.coroutines.flow.flow<NetworkState> { throw IllegalStateException("callback closed") }
            } else {
                network.receiveAsFlow()
            }
        }
        pump(failing, log).start()
        runCurrent()
        assertThat(calls).isEqualTo(1)

        advanceTimeBy(9_999)
        runCurrent()
        assertThat(calls).isEqualTo(1)
        advanceTimeBy(1)
        runCurrent()
        network.trySend(NetworkState(NetworkType.WIFI, null, true))
        runCurrent()

        assertThat(calls).isEqualTo(2)
        assertThat(events).hasSize(1)
        assertThat(log.joinToString()).contains("IllegalStateException")
    }

    @Test
    fun stopCancelsCollection() = runTest {
        val p = pump()
        p.start()
        runCurrent()

        p.stop()
        network.trySend(NetworkState(NetworkType.WIFI, null, true))
        runCurrent()

        assertThat(events).isEmpty()
    }

    @Test
    fun startTwiceSubscribesOnce() = runTest {
        val p = pump()

        p.start()
        p.start()
        runCurrent()

        assertThat(networkSubscriptions).isEqualTo(1)
    }
}
