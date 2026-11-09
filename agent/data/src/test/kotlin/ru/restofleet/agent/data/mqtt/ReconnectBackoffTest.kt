package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun doublesFromOneSecondUpToTwoMinutes() {
        val backoff = ReconnectBackoff()

        val delays = List(10) { backoff.next() }

        assertThat(delays).containsExactly(
            1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 120_000L, 120_000L, 120_000L,
        ).inOrder()
    }

    @Test
    fun resetStartsOver() {
        val backoff = ReconnectBackoff()
        repeat(4) { backoff.next() }

        backoff.reset()

        assertThat(backoff.next()).isEqualTo(1_000L)
    }
}
