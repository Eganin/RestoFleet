package ru.restofleet.console.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RelativeTimeTest {
    private data class Row(val ms: Long, val expected: String)

    @Test
    fun `duration table`() {
        listOf(
            Row(-5, "0 с"), Row(0, "0 с"), Row(999, "0 с"), Row(8_000, "8 с"), Row(59_999, "59 с"),
            Row(60_000, "1 мин"), Row(7 * 60_000L, "7 мин"), Row(3_599_999, "59 мин"),
            Row(3_600_000, "1 ч"), Row(3 * 3_600_000L + 12 * 60_000L, "3 ч 12 мин"),
            Row(86_399_999, "23 ч 59 мин"), Row(86_400_000, "1 д 0 ч"), Row(90_000_000, "1 д 1 ч"),
        ).forEach { assertThat(RelativeTime.duration(it.ms)).isEqualTo(it.expected) }
    }

    @Test
    fun `ago is now for future and sub-second`() {
        assertThat(RelativeTime.ago(1_000, 5_000)).isEqualTo("сейчас")
        assertThat(RelativeTime.ago(5_500, 5_000)).isEqualTo("сейчас")
        assertThat(RelativeTime.ago(13_000, 5_000)).isEqualTo("8 с назад")
    }

    @Test
    fun `countdown table`() {
        assertThat(RelativeTime.countdown(-1)).isEqualTo("00:00:00")
        assertThat(RelativeTime.countdown(86_352_000)).isEqualTo("23:59:12")
        assertThat(RelativeTime.countdown(61_000)).isEqualTo("00:01:01")
    }
}
