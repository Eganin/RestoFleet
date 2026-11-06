package ru.restofleet.shared.kernel

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Clock as KotlinClock

class ClockTest {

    @Test
    fun systemClockReturnsCurrentEpochMillis() {
        val before = KotlinClock.System.now().toEpochMilliseconds()
        val now = SystemClock.nowMillis()
        val after = KotlinClock.System.now().toEpochMilliseconds()

        assertThat(now).isAtLeast(before)
        assertThat(now).isAtMost(after)
    }

    @Test
    fun clockIsReplaceableByLambda() {
        val fixed = Clock { 1_793_865_600_000 }
        assertThat(fixed.nowMillis()).isEqualTo(1_793_865_600_000)
    }
}
