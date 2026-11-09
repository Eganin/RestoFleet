package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.fake.FakeBrokerSession
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.fake.FakePayloadEncoder
import ru.restofleet.agent.domain.fake.FakeShadowStore
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Clock

class ApplyDesiredShadowTest {
    private val store = FakeShadowStore()
    private val session = FakeBrokerSession()
    private val publisher = FakeMessagePublisher()
    private val outbox = FakeOutbox()
    private val apply = ApplyDesiredShadow(
        store,
        PublishOrBuffer(session, publisher, outbox),
        FakePayloadEncoder(),
        Clock { 9_000L },
    )

    private fun shadow(
        version: Long = 12,
        interval: Int = 30,
        probes: List<ProbeTarget> = listOf(ProbeTarget("api", "https://api.example/health", 5_000)),
    ) = DesiredShadow(version, interval, listOf("com.android.chrome"), probes)

    private fun sent() = publisher.published.map { String(it.payload) }

    @Test
    fun newerVersionIsSavedAndReported() = runTest {
        store.version = 11

        apply(shadow(12))

        assertThat(store.saved).containsExactly(shadow(12))
        assertThat(sent()).containsExactly("reported:v12:i30:papi:e[]:at9000")
        val message = publisher.published.single()
        assertThat(message.kind).isEqualTo(MessageKind.SHADOW_REPORTED)
        assertThat(message.qos).isEqualTo(1)
        assertThat(message.retain).isFalse()
        assertThat(message.createdAt).isEqualTo(9_000L)
    }

    @Test
    fun firstShadowWithoutStoredVersionIsApplied() = runTest {
        apply(shadow(1))

        assertThat(store.saved).hasSize(1)
        assertThat(sent()).hasSize(1)
    }

    @Test
    fun olderVersionIsIgnored() = runTest {
        store.version = 12

        apply(shadow(10))

        assertThat(store.saved).isEmpty()
        assertThat(publisher.calls).isEqualTo(0)
        assertThat(outbox.messages).isEmpty()
    }

    @Test
    fun sameVersionOnlyRepeatsReported() = runTest {
        store.version = 12

        apply(shadow(12))

        assertThat(store.saved).isEmpty()
        assertThat(sent()).containsExactly("reported:v12:i30:papi:e[]:at9000")
    }

    @Test
    fun reportedIsBufferedWhenDisconnected() = runTest {
        session.state.value = SessionState.DISCONNECTED

        apply(shadow(12))

        assertThat(publisher.calls).isEqualTo(0)
        assertThat(outbox.messages).hasSize(1)
        assertThat(store.saved).hasSize(1)
    }

    @Test
    fun intervalBoundsAreAccepted() = runTest {
        apply(shadow(1, interval = 10))
        apply(shadow(2, interval = 3_600))

        assertThat(store.saved.map { it.telemetryIntervalSec }).containsExactly(10, 3_600).inOrder()
        assertThat(sent().all { it.endsWith("e[]:at9000") }).isTrue()
    }

    @Test
    fun intervalBelowRangeIsNotAppliedAndReported() = runTest {
        apply(shadow(1, interval = 9))

        assertThat(store.saved.single().telemetryIntervalSec).isEqualTo(30)
        assertThat(sent()).containsExactly("reported:v1:i30:papi:e[telemetryIntervalSec:out_of_range]:at9000")
    }

    @Test
    fun intervalAboveRangeIsNotApplied() = runTest {
        apply(shadow(1, interval = 3_601))

        assertThat(store.saved.single().telemetryIntervalSec).isEqualTo(30)
        assertThat(sent().single()).contains("e[telemetryIntervalSec:out_of_range]")
    }

    @Test
    fun duplicateProbeIdKeepsFirstAndReportsError() = runTest {
        val first = ProbeTarget("api", "https://a.example/", 5_000)
        val second = ProbeTarget("api", "https://b.example/", 5_000)

        apply(shadow(1, probes = listOf(first, second)))

        assertThat(store.saved.single().probes).containsExactly(first)
        assertThat(sent().single()).contains("e[probes:duplicate_id:api]")
    }

    @Test
    fun blankIdAndBlankUrlProbesAreDropped() = runTest {
        val good = ProbeTarget("api", "https://a.example/", 5_000)

        apply(shadow(1, probes = listOf(ProbeTarget(" ", "https://x/", 1), ProbeTarget("kds", "", 1), good)))

        assertThat(store.saved.single().probes).containsExactly(good)
        assertThat(sent().single()).contains("e[probes:empty_id, probes:empty_url:kds]")
    }

    // M7: неверный интервал не применяется — остаётся прежний применённый, а не 30 с (Ruling 279)
    @Test
    fun invalidIntervalKeepsThePreviouslyAppliedValue() = runTest {
        apply(shadow(1, interval = 60))

        apply(shadow(2, interval = 5))

        assertThat(store.saved.map { it.telemetryIntervalSec }).containsExactly(60, 60).inOrder()
        assertThat(sent().last()).isEqualTo("reported:v2:i60:papi:e[telemetryIntervalSec:out_of_range]:at9000")
    }

    @Test
    fun repeatOfSameVersionStillReportsValidationErrors() = runTest {
        store.version = 5

        apply(shadow(5, interval = 1))

        assertThat(store.saved).isEmpty()
        assertThat(sent().single()).contains("telemetryIntervalSec:out_of_range")
    }
}

class ApplyDesiredShadowWatchedAppsTest {
    private val store = FakeShadowStore()
    private val publisher = FakeMessagePublisher()
    private val apply = ApplyDesiredShadow(
        store,
        PublishOrBuffer(FakeBrokerSession(), publisher, FakeOutbox()),
        FakePayloadEncoder(),
        Clock { 9_000L },
    )

    @Test
    fun invalidAndBlankPackageNamesAreDroppedAndReported() = runTest {
        apply(DesiredShadow(1, 30, listOf("com.android.chrome", "", "bad name", "x"), emptyList()))

        assertThat(store.saved.single().watchedApps).containsExactly("com.android.chrome")
        val report = String(publisher.published.single().payload)
        assertThat(report).contains("watchedApps:empty")
        assertThat(report).contains("watchedApps:invalid_name:bad name")
        assertThat(report).contains("watchedApps:invalid_name:x")
    }

    @Test
    fun duplicatePackageNamesAreCollapsedSilently() = runTest {
        apply(DesiredShadow(1, 30, listOf("a.b", "a.b"), emptyList()))

        assertThat(store.saved.single().watchedApps).containsExactly("a.b")
        assertThat(String(publisher.published.single().payload)).contains("e[]")
    }
}
