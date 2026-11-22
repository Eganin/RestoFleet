package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.fake.CapturingPayloadEncoder
import ru.restofleet.agent.domain.fake.FakeBrokerSession
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.fake.FakeShadowStore
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.ForegroundApp
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.PowerSource
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.model.ThresholdMetric
import ru.restofleet.agent.domain.model.Thresholds
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Clock

/** Пороги и передний план берутся из применённой тени, события уходят через [PublishEvent] (QoS 1, outbox). */
class EvaluateThresholdsTest {
    private val session = FakeBrokerSession()
    private val publisher = FakeMessagePublisher()
    private val outbox = FakeOutbox()
    private val encoder = CapturingPayloadEncoder()
    private val shadow = FakeShadowStore()
    private val sut = EvaluateThresholds(
        shadow,
        PublishEvent(PublishOrBuffer(session, publisher, outbox), encoder, Clock { 5 }),
    )

    private fun snapshot(level: Int, fg: String? = null, ts: Long = 1_000) = TelemetrySnapshot(
        ts = ts, seq = 1, battery = BatteryState(level, false, PowerSource.NONE, null), thermal = null,
        network = null, resources = null, foregroundApp = fg?.let { ForegroundApp(it, 0) }, apps = emptyList(),
        unavailable = emptyMap(),
    )

    @Test
    fun withoutShadowDefaultThresholdsApply() = runTest {
        sut(snapshot(level = 20))

        assertThat(encoder.events).containsExactly(
            AgentEvent.ThresholdCrossed(1_000, ThresholdMetric.BATTERY_LOW, true, 20.0, 20.0),
        )
    }

    @Test
    fun thresholdsAndWatchedAppsComeFromAppliedShadow() = runTest {
        shadow.save(
            DesiredShadow(
                version = 2,
                telemetryIntervalSec = 30,
                watchedApps = listOf("com.android.chrome"),
                probes = emptyList(),
                thresholds = Thresholds(batteryLowPct = 50, tempHighC = 45.0, fgAbsentMin = 1),
            ),
        )

        sut(snapshot(level = 40, fg = "com.android.settings", ts = 120_000))

        assertThat(encoder.events.map { it::class.simpleName })
            .containsExactly("ThresholdCrossed", "ForegroundAbsent").inOrder()
        assertThat((encoder.events[1] as AgentEvent.ForegroundAbsent).pkg).isEqualTo("com.android.chrome")
    }

    @Test
    fun stateSurvivesBetweenTicks() = runTest {
        sut(snapshot(level = 10))
        sut(snapshot(level = 11))
        sut(snapshot(level = 30))

        assertThat(encoder.events.map { (it as AgentEvent.ThresholdCrossed).entered }).containsExactly(true, false)
    }

    @Test
    fun foregroundChangeIsPublished() = runTest {
        sut(snapshot(level = 80, fg = "a.b"))
        sut(snapshot(level = 80, fg = "c.d"))

        assertThat(encoder.events).containsExactly(AgentEvent.ForegroundChanged(1_000, "a.b", "c.d"))
    }

    @Test
    fun eventsWaitInOutboxWithoutConnection() = runTest {
        session.state.value = SessionState.DISCONNECTED

        sut(snapshot(level = 1))

        assertThat(outbox.messages.single().kind).isEqualTo(MessageKind.EVENT)
        assertThat(publisher.calls).isEqualTo(0)
    }
}
