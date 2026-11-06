package ru.restofleet.agent.data.report

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import ru.restofleet.agent.domain.capability.CapabilityReport
import ru.restofleet.agent.domain.port.CapabilityReportWriter
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Не удалось записать отчёт: [details] — причина для сообщения пользователю. */
data class ReportWriteFailed(val details: String) : DomainError {
    override val code: String = "report_write_failed"
}

/**
 * Пишет отчёт в `<directory>/capability-report-api<N>-<mode>.json` атомарно (tmp + rename), ввод-вывод — на [io].
 * [directory] — `getExternalFilesDir(null)`, который может вернуть `null`: тогда [ReportWriteFailed], не исключение.
 */
class JsonCapabilityReportWriter(
    private val directory: () -> File?,
    private val io: CoroutineDispatcher,
) : CapabilityReportWriter {

    override suspend fun write(report: CapabilityReport): Outcome<String, DomainError> = withContext(io) {
        val dir = directory() ?: return@withContext Outcome.Failure(ReportWriteFailed("external files dir unavailable"))
        try {
            Files.createDirectories(dir.toPath())
            val name = "capability-report-api${report.device.apiLevel}-${report.device.mode.name.lowercase()}.json"
            val target = File(dir, name)
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(JSON.encodeToString(ReportDto.serializer(), report.toDto()))
            Files.move(tmp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            Outcome.Success(target.path)
        } catch (e: IOException) {
            Outcome.Failure(ReportWriteFailed(e.message ?: e::class.simpleName.orEmpty()))
        }
    }

    companion object {
        private val JSON = Json {
            prettyPrint = true
            explicitNulls = true
        }

        fun forContext(context: Context, io: CoroutineDispatcher) =
            JsonCapabilityReportWriter({ context.getExternalFilesDir(null) }, io)
    }
}
