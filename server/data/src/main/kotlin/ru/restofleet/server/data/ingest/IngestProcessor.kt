package ru.restofleet.server.data.ingest

import io.github.oshai.kotlinlogging.KotlinLogging
import ru.restofleet.server.domain.command.usecase.HandleCommandResult
import ru.restofleet.server.domain.ingest.DeviceDataBatch
import ru.restofleet.server.domain.ingest.IngestOutcome
import ru.restofleet.server.domain.ingest.usecase.IngestBatch
import ru.restofleet.server.domain.presence.usecase.HandleStatus
import ru.restofleet.server.domain.shadow.usecase.HandleReported

/** Обработка пачки входящих: исключение = пачка не записана (её не подтверждают). */
fun interface InboundProcessor {
    suspend fun process(messages: List<InboundMessage>)
}

/**
 * Разбор пачки и вызов use cases: сначала `status` (по порядку прихода), затем одна транзакция
 * [IngestBatch] на телеметрию, пробы и события, затем `reported` и `cmd/res`. Каждый use case фиксирует свою
 * транзакцию; при передоставке пачки повтор безопасен: телеметрия и пробы — `ON CONFLICT DO NOTHING`, статус и
 * команды идемпотентны, событие может задвоиться (реш. 207).
 */
class IngestProcessor(
    private val decoder: DeviceMessageDecoder,
    private val handleStatus: HandleStatus,
    private val ingestBatch: IngestBatch,
    private val handleReported: HandleReported,
    private val handleCommandResult: HandleCommandResult,
    private val metrics: IngestMetrics,
) : InboundProcessor {
    private val log = KotlinLogging.logger {}

    override suspend fun process(messages: List<InboundMessage>) {
        val decoded = messages.map { message ->
            decoder.decode(message.topic, message.payload).also {
                if (it is Decoded.Undecodable) {
                    metrics.undecodable.incrementAndGet()
                    // Без payload: в нём могут быть данные устройства
                    log.warn { "undecodable message on ${message.topic}: ${it.reason}" }
                }
                if (it is Decoded.Ignored) metrics.ignored.incrementAndGet()
            }
        }
        decoded.filterIsInstance<Decoded.Status>().forEach { count(handleStatus(it.report)) }
        val batch = DeviceDataBatch(
            telemetry = decoded.filterIsInstance<Decoded.Telemetry>().map { it.snapshot },
            probes = decoded.filterIsInstance<Decoded.Probe>().map { it.report },
            events = decoded.filterIsInstance<Decoded.Event>().map { it.event },
        )
        val result = ingestBatch(batch)
        metrics.applied.addAndGet((batch.size - result.dropped).toLong())
        metrics.dropped.addAndGet(result.dropped.toLong())
        decoded.filterIsInstance<Decoded.Reported>().forEach { count(handleReported(it.report)) }
        decoded.filterIsInstance<Decoded.CommandResult>().forEach { count(handleCommandResult(it.report)) }
    }

    private fun count(outcome: IngestOutcome) {
        when (outcome) {
            IngestOutcome.APPLIED -> metrics.applied.incrementAndGet()
            IngestOutcome.IGNORED_STALE -> metrics.ignored.incrementAndGet()
            IngestOutcome.DROPPED_UNKNOWN_DEVICE -> metrics.dropped.incrementAndGet()
        }
    }
}
