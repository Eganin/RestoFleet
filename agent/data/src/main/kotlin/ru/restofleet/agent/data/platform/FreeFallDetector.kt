package ru.restofleet.agent.data.platform

import kotlin.math.sqrt

/** Свободное падение: модуль ускорения ниже порога непрерывно не менее [minDurationMs]. Не потокобезопасен. */
class FreeFallDetector(
    private val thresholdMs2: Float = DEFAULT_THRESHOLD_MS2,
    private val minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
) {
    private var fallStartedAt: Long? = null

    fun onSample(x: Float, y: Float, z: Float, ts: Long): Boolean {
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude >= thresholdMs2) {
            fallStartedAt = null
            return false
        }
        val start = fallStartedAt ?: ts.also { fallStartedAt = it }
        return ts - start >= minDurationMs
    }

    private companion object {
        const val DEFAULT_THRESHOLD_MS2 = 2.0f
        const val DEFAULT_MIN_DURATION_MS = 100L
    }
}
