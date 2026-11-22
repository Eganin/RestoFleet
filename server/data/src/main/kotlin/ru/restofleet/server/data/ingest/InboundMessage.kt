package ru.restofleet.server.data.ingest

/** Входящее сообщение устройства; [ack] — подтверждение брокеру (PUBACK), строго после commit (P10). */
class InboundMessage(val topic: String, val payload: ByteArray, private val acknowledge: () -> Unit) {
    fun ack() = acknowledge()
}
