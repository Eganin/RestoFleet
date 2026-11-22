package ru.restofleet.server.data.ingest

/** Входящее сообщение устройства; [ack] — подтверждение брокеру (PUBACK), строго после commit (P10). */
class InboundMessage(
    val topic: String,
    val payload: ByteArray,
    /** QoS публикации: 0 — живая телеметрия, её очередь в [IngestPipeline] ограничена (остаток R-1/R-2). */
    val qos: Int = 1,
    private val acknowledge: () -> Unit,
) {
    fun ack() = acknowledge()
}
