package ru.restofleet.agent.domain.threshold

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.AgentEvent.ForegroundAbsent
import ru.restofleet.agent.domain.model.AgentEvent.ForegroundChanged
import ru.restofleet.agent.domain.model.ForegroundApp

/** FR-AG-41, реш. 220: `fg_changed` на каждую смену, `fg_absent` — один раз на эпизод отсутствия. */
class ForegroundTrackerTest {
    private val tracker = ForegroundTracker()
    private val chrome = "com.android.chrome"
    private val settings = "com.android.settings"
    private val launcher = "com.android.launcher"

    private fun tick(
        minute: Int,
        fg: String?,
        since: Long = minute * MIN,
        watched: List<String> = listOf(chrome),
        limit: Int = 10,
    ): List<AgentEvent> = tracker.evaluate(minute * MIN, fg?.let { ForegroundApp(it, since) }, watched, limit)

    @Test
    fun firstObservationIsABaselineNotAChange() {
        assertThat(tick(0, chrome)).isEmpty()
    }

    @Test
    fun everyChangeIsReportedWithPreviousApp() {
        tick(0, chrome)

        assertThat(tick(1, settings)).containsExactly(ForegroundChanged(1 * MIN, chrome, settings))
        assertThat(tick(2, settings)).isEmpty()
        assertThat(tick(3, launcher)).containsExactly(ForegroundChanged(3 * MIN, settings, launcher))
    }

    @Test
    fun unknownForegroundKeepsStateAndSendsNothing() {
        tick(0, chrome)

        assertThat(tick(1, null)).isEmpty()
        assertThat(tick(2, chrome)).isEmpty()
        assertThat(tick(3, settings)).containsExactly(ForegroundChanged(3 * MIN, chrome, settings))
    }

    @Test
    fun absentLongerThanLimitIsReportedOncePerEpisode() {
        tick(0, chrome)
        tick(1, settings) // ушёл с экрана: на экране settings с минуты 1

        assertThat(tick(10, settings)).isEmpty()
        assertThat(tick(11, settings)).containsExactly(ForegroundAbsent(11 * MIN, chrome, 1 * MIN, 10, settings))
        assertThat(tick(12, launcher)).containsExactly(ForegroundChanged(12 * MIN, settings, launcher))
        assertThat(tick(30, launcher)).isEmpty()
    }

    @Test
    fun returnEndsTheEpisodeAndANewOneIsReportedAgain() {
        tick(0, settings, since = 0)
        val first = tick(10, settings, since = 0)
        tick(11, chrome)
        tick(12, settings)
        val second = tick(22, settings, since = 12 * MIN)

        assertThat(first.filterIsInstance<ForegroundAbsent>().single().sinceTs).isEqualTo(0)
        assertThat(second.filterIsInstance<ForegroundAbsent>().single().sinceTs).isEqualTo(12 * MIN)
    }

    @Test
    fun absenceStartsNoLaterThanCurrentAppCameToFront() {
        // Агент только запустился, settings на экране уже 20 минут: отслеживаемого chrome нет как минимум столько же
        val events = tick(30, settings, since = 10 * MIN)

        assertThat(events).containsExactly(ForegroundAbsent(30 * MIN, chrome, 10 * MIN, 10, settings))
    }

    @Test
    fun foregroundTimestampFromTheFutureIsClampedToNow() {
        val events = tick(5, settings, since = 99 * MIN)

        assertThat(events).isEmpty()
        assertThat(tick(15, settings, since = 99 * MIN).filterIsInstance<ForegroundAbsent>().single().sinceTs)
            .isEqualTo(5 * MIN)
    }

    @Test
    fun eachWatchedAppHasItsOwnEpisode() {
        val waiter = "ru.restofleet.dummy.waiter"
        val both = listOf(chrome, waiter)

        tick(0, chrome, watched = both)
        val events = tick(10, chrome, watched = both)

        assertThat(events).containsExactly(ForegroundAbsent(10 * MIN, waiter, 0, 10, chrome))
    }

    @Test
    fun appRemovedFromWatchedForgetsItsEpisode() {
        tick(0, settings, since = 0)
        assertThat(tick(10, settings, since = 0)).hasSize(1)
        tick(11, settings, since = 0, watched = emptyList())

        // Снова в списке — новый эпизод, о нём сообщается заново
        assertThat(tick(12, settings, since = 0)).containsExactly(ForegroundAbsent(12 * MIN, chrome, 0, 10, settings))
    }

    @Test
    fun zeroLimitDisablesAbsenceEvents() {
        assertThat(tick(100, settings, since = 0, limit = 0)).isEmpty()
    }

    private companion object {
        const val MIN = 60_000L
    }
}
