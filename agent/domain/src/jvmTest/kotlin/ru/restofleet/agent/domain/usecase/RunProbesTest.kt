package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.fake.CapturingPayloadEncoder
import ru.restofleet.agent.domain.fake.FakeBrokerSession
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.agent.domain.model.ProbePhases
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.port.ProbeExecutor
import ru.restofleet.agent.domain.probe.ProbeStates
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.probes.ProbeState

/** FR-AG-34, 44 (реш. 226): `probe_state` на каждую смену состояния, отчёт `probe` — на каждый прогон. */
class RunProbesTest {
    private val publisher = FakeMessagePublisher()
    private val encoder = CapturingPayloadEncoder()
    private val publish = PublishOrBuffer(FakeBrokerSession(), publisher, FakeOutbox())
    private val clock = Clock { 500 }
    private val outcomes = mutableMapOf<String, ArrayDeque<Boolean>>()
    private val executor = ProbeExecutor { target ->
        val ok = outcomes.getValue(target.id).removeFirst()
        result(target.id, ok)
    }
    private val states = ProbeStates()
    private val sut =
        RunProbes(executor, states, PublishProbeResult(publish, encoder, clock), PublishEvent(publish, encoder, clock))

    private fun result(id: String, ok: Boolean, ts: Long = 100) = ProbeResult(
        targetId = id,
        ok = ok,
        httpStatus = if (ok) 200 else null,
        error = if (ok) null else ProbeError.TIMEOUT,
        totalMs = 7,
        phases = ProbePhases(null, null, null, null),
        ts = ts,
    )

    private fun target(id: String, failThreshold: Int = 3) =
        ProbeTarget(id, "https://$id.example/", 1_000, failThreshold = failThreshold)

    private fun script(id: String, vararg ok: Boolean) {
        outcomes[id] = ArrayDeque(ok.toList())
    }

    private fun stateEvents() = encoder.events.filterIsInstance<AgentEvent.ProbeStateChanged>()

    @Test
    fun firstSuccessReportsUpAndProbeReport() = runTest {
        script("api", true)

        val results = sut(listOf(target("api")))

        assertThat(results.single().ok).isTrue()
        assertThat(stateEvents())
            .containsExactly(AgentEvent.ProbeStateChanged(100, "api", up = true, error = null, failures = 0))
        assertThat(encoder.probeReports.single().map { it.targetId }).containsExactly("api")
    }

    @Test
    fun downOnlyAfterKConsecutiveFailures() = runTest {
        script("api", true, false, false, false, false)
        val api = target("api", failThreshold = 3)

        repeat(5) { sut(listOf(api)) }

        assertThat(stateEvents().map { it.up to it.failures }).containsExactly(true to 0, false to 3).inOrder()
        assertThat(stateEvents().last().error).isEqualTo(ProbeError.TIMEOUT)
        assertThat(encoder.probeReports).hasSize(5)
    }

    @Test
    fun recoveryAfterDownReportsUpAgain() = runTest {
        script("api", false, true)

        repeat(2) { sut(listOf(target("api", failThreshold = 1))) }

        assertThat(stateEvents().map { it.up }).containsExactly(false, true).inOrder()
    }

    @Test
    fun stateEventGoesBeforeProbeReport() = runTest {
        script("api", true)

        sut(listOf(target("api")))

        assertThat(publisher.published.map { it.kind }).containsExactly(MessageKind.EVENT, MessageKind.PROBE).inOrder()
    }

    @Test
    fun targetsRunConcurrentlyAndShareOneReport() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val concurrent = RunProbes(
            ProbeExecutor { t ->
                started += t.id
                gate.await()
                result(t.id, true)
            },
            states,
            PublishProbeResult(publish, encoder, clock),
            PublishEvent(publish, encoder, clock),
        )

        val run = async { concurrent(listOf(target("a"), target("b"))) }
        runCurrent()
        assertThat(started).containsExactly("a", "b")
        gate.complete(Unit)

        assertThat(run.await().map { it.targetId }).containsExactly("a", "b").inOrder()
        assertThat(encoder.probeReports.single().map { it.targetId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun emptyTargetsPublishNothing() = runTest {
        assertThat(sut(emptyList())).isEmpty()
        assertThat(publisher.published).isEmpty()
    }

    @Test
    fun statesExposeLastResultAndStatePerTarget() = runTest {
        script("api", false)
        script("kds", true)

        sut(listOf(target("api"), target("kds")))

        val byId = states.statuses.value.associateBy { it.targetId }
        assertThat(byId.getValue("api").state).isEqualTo(ProbeState.UNKNOWN)
        assertThat(byId.getValue("api").consecutiveFailures).isEqualTo(1)
        assertThat(byId.getValue("api").last.ok).isFalse()
        assertThat(byId.getValue("kds").state).isEqualTo(ProbeState.UP)
    }

    @Test
    fun changedFailThresholdRestartsTheMachine() = runTest {
        script("api", true, false)

        sut(listOf(target("api", failThreshold = 3)))
        sut(listOf(target("api", failThreshold = 1)))

        // Новый автомат начинает с UNKNOWN: одна неудача при K = 1 — сразу DOWN
        assertThat(stateEvents().map { it.up }).containsExactly(true, false).inOrder()
    }
}
