package ru.restofleet.agent.data.lifecycle

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InMemorySequenceSourceTest {
    @Test
    fun countsFromOneMonotonically() {
        val seq = InMemorySequenceSource()

        assertThat(listOf(seq.next(), seq.next(), seq.next())).containsExactly(1L, 2L, 3L).inOrder()
    }
}
