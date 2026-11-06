package ru.restofleet.agent.data.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HeartbeatGapsTest {

    private val samples = listOf(0L, 30_000L, 60_000L, 200_000L)

    @Test
    fun maxGap() {
        assertThat(HeartbeatGaps.maxGapMillis(samples)).isEqualTo(140_000L)
    }

    @Test
    fun fewerThanTwoPointsIsNull() {
        assertThat(HeartbeatGaps.maxGapMillis(emptyList()) as Any?).isNull()
        assertThat(HeartbeatGaps.maxGapMillis(listOf(5L)) as Any?).isNull()
    }

    @Test
    fun gapsOverLimitAreCountedStrictly() {
        assertThat(HeartbeatGaps.gapsOver(samples, 120_000)).isEqualTo(1)
        assertThat(HeartbeatGaps.gapsOver(samples, 140_000)).isEqualTo(0)
        assertThat(HeartbeatGaps.gapsOver(samples, 20_000)).isEqualTo(3)
        assertThat(HeartbeatGaps.gapsOver(listOf(1L), 10)).isEqualTo(0)
    }
}
