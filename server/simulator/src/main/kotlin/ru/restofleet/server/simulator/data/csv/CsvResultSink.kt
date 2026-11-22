package ru.restofleet.server.simulator.data.csv

import ru.restofleet.server.simulator.domain.model.RegistrationReport
import ru.restofleet.server.simulator.domain.model.RunReport
import ru.restofleet.server.simulator.domain.port.ResultSink
import ru.restofleet.server.simulator.domain.stats.Percentiles
import ru.restofleet.shared.kernel.Clock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

/**
 * Результаты в CSV (FR-SM-07): каталог прогона `<root>/<имя сценария>/` — `summary.csv` (одна строка), `commands.csv`,
 * `detection.csv`, `timeline.csv`; регистрация — строкой в `<root>/registration.csv`. Только `deviceId`, `cmdId` и
 * числа: паролей и токенов в результатах нет.
 */
class CsvResultSink(private val root: Path, private val clock: Clock) : ResultSink {
    override fun registration(report: RegistrationReport) {
        val file = root.resolve(REGISTRATION)
        Files.createDirectories(root)
        if (!Files.exists(file)) {
            Files.writeString(file, "at_ms,requested,cached,registered,failed,duration_ms,failures\n")
        }
        val failures = report.failures.entries.joinToString(";") { "${it.key}=${it.value}" }
        val row = listOf(
            clock.nowMillis(), report.requested, report.cached, report.registered, report.failed, report.durationMs,
            failures,
        )
        Files.writeString(file, row.joinToString(",") { cell(it) } + "\n", StandardOpenOption.APPEND)
    }

    override fun run(report: RunReport) {
        val dir = root.resolve(report.scenario.name)
        Files.createDirectories(dir)
        write(dir.resolve("summary.csv"), SUMMARY_HEADER, listOf(summary(report)))
        write(
            dir.resolve("commands.csv"), "cmd_id,device_id,sent_at_ms,latency_ms,final_status",
            report.commands.map {
                listOf(it.cmdId, it.deviceId, it.sentAtMillis, it.latencyMs?.let(::ms), it.finalStatus)
            },
        )
        write(
            dir.resolve("detection.csv"), "device_id,lost_at_ms,offline_after_ms,last_status",
            report.detections.map { listOf(it.deviceId, it.lostAtMillis, it.offlineAfterMs, it.lastStatus) },
        )
        write(
            dir.resolve("timeline.csv"),
            "wall_ms,phase,connected,msgs_out,msgs_in,requests,request_errors,publish_errors,connection_losses," +
                "payload_out,payload_in,rx_bytes,tx_bytes",
            report.timeline.map { row ->
                val c = row.counters
                listOf(
                    row.wallMillis, row.phase, c.connected, c.messagesOut, c.messagesIn, c.requests, c.requestErrors,
                    c.publishErrors, c.connectionLosses, c.payloadBytesOut, c.payloadBytesIn, row.traffic?.rxBytes,
                    row.traffic?.txBytes,
                )
            },
        )
    }

    private fun summary(report: RunReport): List<Any?> {
        val scenario = report.scenario
        val c = report.windowCounters
        val latencies = Percentiles.summary(report.commands.mapNotNull { it.latencyMs })
        val detected = report.detections.mapNotNull { it.offlineAfterMs?.toDouble() }
        val detection = Percentiles.summary(detected)
        return listOf(
            scenario.name, scenario.mode.code, scenario.network, scenario.devices, scenario.pollIntervalSec,
            scenario.telemetryIntervalSec, report.startedAtMillis, report.rampEndMillis, report.windowStartMillis,
            report.windowEndMillis, (report.windowEndMillis - report.windowStartMillis) / MS_IN_SEC,
            report.connectedAtWindowStart, c.connected, c.messagesOut, c.messagesIn, c.requests, c.requestErrors,
            c.publishErrors, c.connectFailures, c.connectionLosses, c.payloadBytesOut, c.payloadBytesIn,
            report.windowTraffic?.rxBytes, report.windowTraffic?.txBytes, report.commands.size,
            report.commandSendFailures, latencies?.count ?: 0, latencies?.p50?.let(::ms), latencies?.p95?.let(::ms),
            latencies?.max?.let(::ms), latencies?.mean?.let(::ms), report.detections.size, detected.size,
            detection?.p50?.let(::ms), detection?.max?.let(::ms), scenario.loss?.mode?.code,
        )
    }

    private fun write(file: Path, header: String, rows: List<List<Any?>>) {
        val text = buildString {
            append(header).append('\n')
            rows.forEach { row -> append(row.joinToString(",") { cell(it) }).append('\n') }
        }
        Files.writeString(file, text)
    }

    private fun cell(value: Any?): String {
        val text = value?.toString() ?: ""
        return if (text.any { it == ',' || it == '"' || it == '\n' }) "\"${text.replace("\"", "\"\"")}\"" else text
    }

    private fun ms(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private companion object {
        const val REGISTRATION = "registration.csv"
        const val MS_IN_SEC = 1_000L
        const val SUMMARY_HEADER = "name,mode,network,devices,poll_interval_sec,telemetry_interval_sec,started_at_ms," +
            "ramp_end_ms,window_start_ms,window_end_ms,window_sec,connected_at_window_start,connected_at_window_end," +
            "msgs_out,msgs_in,requests,request_errors,publish_errors,connect_failures,connection_losses," +
            "payload_bytes_out,payload_bytes_in,rx_bytes,tx_bytes,commands_sent,command_send_failures," +
            "commands_received,latency_p50_ms,latency_p95_ms,latency_max_ms,latency_mean_ms,loss_devices," +
            "loss_detected,detection_p50_ms,detection_max_ms,loss_mode"
    }
}
