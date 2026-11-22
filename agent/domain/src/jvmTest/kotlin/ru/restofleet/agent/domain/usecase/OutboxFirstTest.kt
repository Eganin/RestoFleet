package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.fake.CapturingPayloadEncoder
import ru.restofleet.agent.domain.fake.FakeBrokerSession
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.fake.FakeShadowStore
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.ForegroundApp
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.model.PowerSource
import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.agent.domain.model.ProbePhases
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.model.Thresholds
import ru.restofleet.agent.domain.port.Outbox
import ru.restofleet.agent.domain.port.ProbeExecutor
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.agent.domain.probe.ProbeStates
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.probes.ProbeState
import kotlin.test.assertFailsWith

/**
 * R-14 (К, реш. 426): автомат проб, зоны порогов и эпизоды `fg_absent` меняются только после того, как событие
 * поставлено в outbox. Сбой outbox не теряет переход: следующий прогон или тик даёт событие снова.
 */
class OutboxFirstTest {
    /** Outbox, у которого [failures] ближайших постановок бросают (сбой Room). */
    private class FlakyOutbox(val delegate: FakeOutbox = FakeOutbox()) : Outbox by delegate {
        var failures = 0

        override suspend fun enqueue(m: OutgoingMessage) {
            if (failures > 0) {
                failures--
                throw IllegalStateException("disk I/O")
            }
            delegate.enqueue(m)
        }
    }

    private val outbox = FlakyOutbox()
    private val encoder = CapturingPayloadEncoder()
    private val clock = Clock { 5 }
    private val publish = PublishOrBuffer(FakeBrokerSession(SessionState.DISCONNECTED), FakeMessagePublisher(), outbox)
    private val events = PublishEvent(publish, encoder, clock)

    private fun queuedEvents() = outbox.delegate.enqueued.filter { it.kind == MessageKind.EVENT }

    private val target = ProbeTarget("api", "https://api.example/", 1_000, failThreshold = 1)
    private val states = ProbeStates()
    private var ok = false
    private val probes = RunProbes(
        executor = ProbeExecutor { ProbeResult(it.id, ok, null, ProbeError.TIMEOUT.takeIf { !ok }, 7, phases, 100) },
        states = states,
        publishProbes = PublishProbeResult(publish, encoder, clock),
        publishEvent = events,
    )

    @Test
    fun probeTransitionIsNotLostWhenTheOutboxFails() = runTest {
        outbox.failures = 1
        assertFailsWith<IllegalStateException> { probes(listOf(target)) }
        assertThat(states.statuses.value).isEmpty()
        assertThat(queuedEvents()).isEmpty()

        probes(listOf(target))

        assertThat(states.statuses.value.single().state).isEqualTo(ProbeState.DOWN)
        assertThat(queuedEvents()).hasSize(1)
    }

    @Test
    fun previewDoesNotChangeTheMachine() {
        val failed = ProbeResult("api", false, null, ProbeError.TIMEOUT, 7, phases, 100)
        val k3 = target.copy(failThreshold = 3)
        repeat(2) { states.record(k3, failed) }

        assertThat(states.preview(k3, failed)?.to).isEqualTo(ProbeState.DOWN)
        assertThat(states.preview(k3, failed)?.to).isEqualTo(ProbeState.DOWN)
        assertThat(states.statuses.value.single().consecutiveFailures).isEqualTo(2)
    }

    private val shadow = FakeShadowStore()
    private val thresholds = EvaluateThresholds(shadow, events)

    private fun snapshot(level: Int, fg: String, ts: Long) = TelemetrySnapshot(
        ts = ts, seq = 1, battery = BatteryState(level, false, PowerSource.NONE, null), thermal = null,
        network = null, resources = null, foregroundApp = ForegroundApp(fg, 0), apps = emptyList(),
        unavailable = emptyMap(),
    )

    @Test
    fun thresholdAndForegroundEpisodesAreRetriedAfterAnOutboxFailure() = runTest {
        shadow.save(
            DesiredShadow(
                version = 1, telemetryIntervalSec = 30, watchedApps = listOf("ru.restofleet.dummy.waiter"),
                probes = emptyList(), thresholds = Thresholds(batteryLowPct = 20, fgAbsentMin = 1),
            ),
        )
        thresholds(snapshot(level = 50, fg = "com.android.chrome", ts = 0))

        outbox.failures = 1
        assertFailsWith<IllegalStateException> {
            thresholds(snapshot(level = 10, fg = "com.android.launcher", ts = 61_000))
        }
        assertThat(queuedEvents()).isEmpty()

        thresholds(snapshot(level = 10, fg = "com.android.launcher", ts = 62_000))

        // Вход в зону заряда, смена переднего плана и fg_absent — все три снова, ни один не потерян
        assertThat(queuedEvents()).hasSize(3)
    }

    private companion object {
        val phases = ProbePhases(null, null, null, null)
    }
}
