package ru.restofleet.agent.data.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.model.ForegroundApp

class ForegroundAppResolverTest {

    // type: 1 = ACTIVITY_RESUMED, 2 = ACTIVITY_PAUSED

    @Test
    fun emptyListIsNull() {
        assertThat(ForegroundAppResolver.resolve(emptyList()) as Any?).isNull()
    }

    @Test
    fun lastResumedByTimestampWinsRegardlessOfOrder() {
        val events = listOf(
            UsageEventRaw("b", 1, 300),
            UsageEventRaw("a", 1, 100),
            UsageEventRaw("c", 1, 200),
        )
        assertThat(ForegroundAppResolver.resolve(events)).isEqualTo(ForegroundApp("b", 300))
    }

    @Test
    fun pauseAfterResumeOfSamePackageIsNull() {
        val events = listOf(UsageEventRaw("a", 2, 200), UsageEventRaw("a", 1, 100))
        assertThat(ForegroundAppResolver.resolve(events) as Any?).isNull()
    }

    @Test
    fun pauseOfOtherPackageOrEarlierPauseDoesNotCancel() {
        val events = listOf(
            UsageEventRaw("a", 2, 50),
            UsageEventRaw("a", 1, 100),
            UsageEventRaw("b", 2, 200),
        )
        assertThat(ForegroundAppResolver.resolve(events)).isEqualTo(ForegroundApp("a", 100))
    }

    @Test
    fun onlyNonResumeEventsIsNull() {
        val events = listOf(UsageEventRaw("a", 2, 100), UsageEventRaw("a", 23, 200))
        assertThat(ForegroundAppResolver.resolve(events) as Any?).isNull()
    }

    @Test
    fun previousAppSurvivesWindowWithoutEvents() {
        val previous = ForegroundApp("a", 100)

        assertThat(ForegroundAppResolver.resolve(emptyList(), previous)).isEqualTo(previous)
    }

    @Test
    fun newerResumeReplacesPreviousApp() {
        val events = listOf(UsageEventRaw("b", 1, 300))

        assertThat(ForegroundAppResolver.resolve(events, ForegroundApp("a", 100))).isEqualTo(ForegroundApp("b", 300))
    }

    @Test
    fun olderResumeInOverlapDoesNotReplacePreviousApp() {
        val events = listOf(UsageEventRaw("b", 1, 90))

        assertThat(ForegroundAppResolver.resolve(events, ForegroundApp("a", 100))).isEqualTo(ForegroundApp("a", 100))
    }

    @Test
    fun pauseOfPreviousAppWithoutNewResumeIsNull() {
        val events = listOf(UsageEventRaw("a", 2, 200))

        assertThat(ForegroundAppResolver.resolve(events, ForegroundApp("a", 100)) as Any?).isNull()
    }

    @Test
    fun pauseOfPreviousAppFollowedByNewResumeOfSameAppIsThatApp() {
        val events = listOf(UsageEventRaw("a", 2, 200), UsageEventRaw("a", 1, 250))

        assertThat(ForegroundAppResolver.resolve(events, ForegroundApp("a", 100))).isEqualTo(ForegroundApp("a", 250))
    }
}
