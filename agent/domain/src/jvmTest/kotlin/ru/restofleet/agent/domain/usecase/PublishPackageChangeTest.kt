package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.fake.CapturingPayloadEncoder
import ru.restofleet.agent.domain.fake.FakeBrokerSession
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.model.AgentEvent.PackageChanged
import ru.restofleet.agent.domain.model.AgentEvent.PackageChanged.Change
import ru.restofleet.shared.kernel.Clock

/** Реш. 204: одинаковое `(pkg, change, versionCode)` в окне 5 с — одно событие. */
class PublishPackageChangeTest {
    private var now = 10_000L
    private val clock = Clock { now }
    private val encoder = CapturingPayloadEncoder()
    private val publishEvent =
        PublishEvent(PublishOrBuffer(FakeBrokerSession(), FakeMessagePublisher(), FakeOutbox()), encoder, clock)
    private val sut = PublishPackageChange(publishEvent, clock)

    private fun sent() = encoder.events.map { it as PackageChanged }

    @Test
    fun firstChangeIsPublishedWithCurrentTime() = runTest {
        sut("com.android.chrome", Change.REPLACED, 42)

        assertThat(sent()).containsExactly(PackageChanged(10_000, "com.android.chrome", Change.REPLACED, 42))
    }

    @Test
    fun sameChangeWithinWindowIsDropped() = runTest {
        sut("p.a", Change.REPLACED, 42)
        now += 4_999
        sut("p.a", Change.REPLACED, 42)

        assertThat(sent()).hasSize(1)
    }

    @Test
    fun sameChangeAfterWindowIsPublishedAgain() = runTest {
        sut("p.a", Change.REMOVED, null)
        now += 5_000
        sut("p.a", Change.REMOVED, null)

        assertThat(sent()).hasSize(2)
    }

    @Test
    fun differentChangeVersionOrPackageIsNotADuplicate() = runTest {
        sut("p.a", Change.REPLACED, 42)
        sut("p.a", Change.REPLACED, 43)
        sut("p.a", Change.REMOVED, null)
        sut("p.b", Change.REPLACED, 42)

        assertThat(sent()).hasSize(4)
    }

    @Test
    fun windowIsCountedFromLastPublishedNotFromDroppedDuplicate() = runTest {
        sut("p.a", Change.ADDED, 1)
        now += 3_000
        sut("p.a", Change.ADDED, 1) // дубль, отброшен
        now += 2_500 // 5,5 с от первого
        sut("p.a", Change.ADDED, 1)

        assertThat(sent()).hasSize(2)
    }

    @Test
    fun clockGoingBackwardsDoesNotSuppressForever() = runTest {
        sut("p.a", Change.ADDED, 1)
        now -= 60_000
        sut("p.a", Change.ADDED, 1)

        assertThat(sent()).hasSize(2)
    }
}
