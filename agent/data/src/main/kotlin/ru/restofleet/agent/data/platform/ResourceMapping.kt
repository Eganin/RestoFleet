package ru.restofleet.agent.data.platform

/** Единицы измерения ресурсов устройства. */
object ResourceMapping {
    private const val BYTES_IN_MB = 1_048_576L
    private const val MS_IN_SEC = 1_000L

    fun bytesToMb(bytes: Long): Long = bytes / BYTES_IN_MB

    fun uptimeSec(elapsedRealtimeMs: Long): Long = elapsedRealtimeMs / MS_IN_SEC
}
