package ru.restofleet.server.data.ingest

import java.util.concurrent.atomic.AtomicLong

/** Счётчики ingest (для лога и `/health` в КМ2b); потокобезопасные. */
class IngestMetrics {
    val received = AtomicLong()
    val batches = AtomicLong()
    val applied = AtomicLong()
    val ignored = AtomicLong()
    val undecodable = AtomicLong()
    val dropped = AtomicLong()
    val retries = AtomicLong()
    val poison = AtomicLong()

    fun snapshot(): Map<String, Long> = mapOf(
        "received" to received.get(),
        "batches" to batches.get(),
        "applied" to applied.get(),
        "ignored" to ignored.get(),
        "undecodable" to undecodable.get(),
        "dropped" to dropped.get(),
        "retries" to retries.get(),
        "poison" to poison.get(),
    )
}
