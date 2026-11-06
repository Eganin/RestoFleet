package ru.restofleet.agent.data.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FreeFallDetectorTest {

    private fun FreeFallDetector.feedFall(from: Long, to: Long, step: Long = 20): Boolean {
        var result = false
        var ts = from
        while (ts <= to) {
            result = onSample(0.1f, 0.1f, 0.1f, ts)
            ts += step
        }
        return result
    }

    @Test
    fun fallLongerThanMinDurationIsDetected() {
        assertThat(FreeFallDetector().feedFall(0, 120)).isTrue()
    }

    @Test
    fun shortFallIsIgnored() {
        assertThat(FreeFallDetector().feedFall(0, 80)).isFalse()
    }

    @Test
    fun returnToGravityResetsCounter() {
        val d = FreeFallDetector()
        assertThat(d.feedFall(0, 80)).isFalse()
        assertThat(d.onSample(0f, 0f, 9.8f, 100)).isFalse()
        // новая серия начинается с нуля: 80 мс после сброса — ещё не падение
        assertThat(d.feedFall(120, 200)).isFalse()
        assertThat(d.feedFall(220, 240)).isTrue()
    }

    @Test
    fun normalGravityNeverTriggers() {
        assertThat(FreeFallDetector().onSample(0f, 0f, 9.8f, 0)).isFalse()
    }
}
