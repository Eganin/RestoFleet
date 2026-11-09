package ru.restofleet.agent.data.probes

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.ProbePhases
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.port.ShadowStore

@OptIn(ExperimentalCoroutinesApi::class)
class ProbeLoopTest {
    private class Store(initial: DesiredShadow?) : ShadowStore {
        val state = MutableStateFlow(initial)
        override suspend fun appliedVersion(): Long? = state.value?.version
        override suspend fun save(shadow: DesiredShadow) {
            state.value = shadow
        }
        override suspend fun load(): DesiredShadow? = state.value
        override fun observe(): Flow<DesiredShadow?> = state
    }

    private fun target(id: String, sec: Int) = ProbeTarget(id, "https://$id.example/", 1_000, sec)

    private fun shadow(version: Long, vararg targets: ProbeTarget) =
        DesiredShadow(version, 30, emptyList(), targets.toList())

    private fun result(t: ProbeTarget) = ProbeResult(t.id, true, 200, null, 5, ProbePhases(1, 1, 1, 1), 0)

    private val executed = mutableListOf<String>()
    private val published = mutableListOf<List<ProbeResult>>()
    private val logs = mutableListOf<String>()
    private var executorFailure: (ProbeTarget) -> Throwable? = { null }

    private fun TestScope.loop(store: Store) = ProbeLoop(
        scope = backgroundScope,
        shadow = store,
        executor = { t ->
            executed += t.id
            executorFailure(t)?.let { throw it }
            result(t)
        },
        publish = { published += it },
        log = { logs += it },
    )

    private fun ids() = published.map { it.single().targetId }

    @Test
    fun twoTargetsAreProbedEachOnItsOwnInterval() = runTest {
        val store = Store(shadow(1, target("a", 30), target("b", 60)))
        loop(store).start()

        advanceTimeBy(120_000)

        assertThat(ids().count { it == "a" }).isEqualTo(4)
        assertThat(ids().count { it == "b" }).isEqualTo(2)
    }

    @Test
    fun noShadowMeansNoProbes() = runTest {
        loop(Store(null)).start()

        advanceTimeBy(120_000)

        assertThat(published).isEmpty()
    }

    @Test
    fun changedTargetSetReplacesTheRunningOnes() = runTest {
        val store = Store(shadow(1, target("a", 30)))
        loop(store).start()
        advanceTimeBy(31_000)
        assertThat(ids()).containsExactly("a", "a")

        store.save(shadow(2, target("b", 30)))
        advanceTimeBy(31_000)

        assertThat(ids()).containsExactly("a", "a", "b", "b").inOrder()
    }

    @Test
    fun unrelatedShadowChangeDoesNotRestartTheProbes() = runTest {
        val store = Store(shadow(1, target("a", 30)))
        loop(store).start()
        advanceTimeBy(10_000)

        store.save(shadow(2, target("a", 30)))
        advanceTimeBy(10_000)

        assertThat(ids()).containsExactly("a")
    }

    @Test
    fun executorFailureDoesNotStopTheLoop() = runTest {
        var failures = 1
        executorFailure = { if (failures-- > 0) IllegalStateException("boom") else null }
        val store = Store(shadow(1, target("a", 30)))
        loop(store).start()

        advanceTimeBy(70_000)

        assertThat(executed).hasSize(3)
        assertThat(published).hasSize(2)
        assertThat(logs.single()).contains("IllegalStateException")
        assertThat(logs.single()).doesNotContain("boom")
    }

    @Test
    fun failingPublicationDoesNotStopTheLoop() = runTest {
        val store = Store(shadow(1, target("a", 30)))
        var calls = 0
        val loop = ProbeLoop(
            backgroundScope,
            store,
            { result(it) },
            { if (calls++ == 0) error("db") else published += it },
        )
        loop.start()

        advanceTimeBy(40_000)

        assertThat(published).hasSize(1)
    }

    @Test
    fun stopHaltsTheProbes() = runTest {
        val store = Store(shadow(1, target("a", 30)))
        val loop = loop(store)
        loop.start()
        advanceTimeBy(1_000)

        loop.stop()
        advanceTimeBy(120_000)

        assertThat(published).hasSize(1)
    }

    @Test
    fun secondStartWhileRunningDoesNothing() = runTest {
        val store = Store(shadow(1, target("a", 30)))
        val loop = loop(store)
        loop.start()
        loop.start()

        advanceTimeBy(1_000)

        assertThat(published).hasSize(1)
    }

    @Test
    fun tooSmallIntervalIsRaisedSoTheLoopDoesNotSpin() = runTest {
        val store = Store(shadow(1, target("a", 0)))
        loop(store).start()

        advanceTimeBy(10_000)

        assertThat(published.size).isAtMost(2)
    }

    @Test
    fun restartAfterAFailedShadowStreamResubscribes() = runTest {
        var subscriptions = 0
        val store = object : ShadowStore {
            override suspend fun appliedVersion(): Long? = null
            override suspend fun save(shadow: DesiredShadow) = Unit
            override suspend fun load(): DesiredShadow? = null
            override fun observe(): Flow<DesiredShadow?> {
                subscriptions++
                return if (subscriptions == 1) {
                    kotlinx.coroutines.flow.flow { error("io") }
                } else {
                    kotlinx.coroutines.flow.flowOf(shadow(1, target("a", 30)))
                }
            }
        }
        ProbeLoop(backgroundScope, store, { result(it) }, { published += it }).start()

        advanceTimeBy(15_000)

        assertThat(subscriptions).isAtLeast(2)
        assertThat(published).hasSize(1)
    }
}
