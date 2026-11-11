package ru.restofleet.shared.probes

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.test.assertFailsWith

class ProbeScheduleTest {

    @Test
    fun defaultMinimumIsThirtySeconds() {
        val schedule = ProbeSchedule()
        assertThat(schedule.minIntervalSec).isEqualTo(30)
        val cases = mapOf(1 to 30, 5 to 30, 30 to 30, 31 to 31, 300 to 300)
        cases.forEach { (requested, effective) ->
            assertWithMessage("$requested").that(schedule.intervalSec(requested)).isEqualTo(effective)
        }
        assertThat(schedule.intervalMs(60)).isEqualTo(60_000L)
    }

    @Test
    fun standAllowsFiveSeconds() {
        val schedule = ProbeSchedule.forStand()
        assertThat(schedule.minIntervalSec).isEqualTo(5)
        assertThat(schedule.intervalSec(1)).isEqualTo(5)
        assertThat(schedule.intervalSec(10)).isEqualTo(10)
    }

    @Test
    fun minimumBelowStandFloorIsRejected() {
        assertFailsWith<IllegalArgumentException> { ProbeSchedule(minIntervalSec = 4) }
    }

    @Test
    fun nextRunTable() {
        val schedule = ProbeSchedule()
        data class Case(val name: String, val last: Long?, val requested: Int, val now: Long, val next: Long)
        listOf(
            Case("never ran -> now", null, 60, 1_000, 1_000),
            Case("on schedule", 1_000, 60, 2_000, 61_000),
            Case("requested below minimum -> minimum", 1_000, 5, 2_000, 31_000),
            Case("overdue -> now", 1_000, 60, 100_000, 100_000),
            Case("last run in the future (clock went back) -> from now", 500_000, 60, 1_000, 61_000),
        ).forEach { case ->
            assertWithMessage(case.name)
                .that(schedule.nextRunAt(case.last, case.requested, case.now)).isEqualTo(case.next)
        }
    }
}
