package ru.restofleet.agent.data.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UsageWindowTest {
    @Test
    fun firstQueryStartsAtDeviceBoot() {
        assertThat(UsageWindow.startMillis(now = 100_000, elapsedRealtime = 40_000, lastQueryAt = null))
            .isEqualTo(60_000L)
    }

    @Test
    fun nextQueryStartsAtPreviousOneMinusOverlap() {
        assertThat(UsageWindow.startMillis(now = 200_000, elapsedRealtime = 150_000, lastQueryAt = 120_000))
            .isEqualTo(120_000L - UsageWindow.OVERLAP_MS)
    }

    @Test
    fun windowNeverStartsBeforeBoot() {
        // boot = 190_000; предыдущий запрос случился сразу после загрузки, нахлёст ушёл бы раньше неё
        assertThat(UsageWindow.startMillis(now = 200_000, elapsedRealtime = 10_000, lastQueryAt = 195_000))
            .isEqualTo(190_000L)
    }

    @Test
    fun bootBeforeEpochIsClampedToZero() {
        assertThat(UsageWindow.startMillis(now = 1_000, elapsedRealtime = 5_000, lastQueryAt = null)).isEqualTo(0L)
    }

    @Test
    fun windowNeverStartsAfterNow() {
        // часы ушли назад (NTP): lastQueryAt в будущем относительно now
        assertThat(UsageWindow.startMillis(now = 100_000, elapsedRealtime = 40_000, lastQueryAt = 500_000))
            .isEqualTo(100_000L)
    }
}
