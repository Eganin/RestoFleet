package ru.restofleet.agent.data.platform

/** Разрывы между отметками heartbeat (epoch millis, по возрастанию). */
object HeartbeatGaps {
    fun maxGapMillis(timestamps: List<Long>): Long? = gaps(timestamps).maxOrNull()

    /** Количество разрывов строго больше [limitMs]. */
    fun gapsOver(timestamps: List<Long>, limitMs: Long): Int = gaps(timestamps).count { it > limitMs }

    private fun gaps(timestamps: List<Long>): List<Long> = timestamps.zipWithNext { a, b -> b - a }
}
