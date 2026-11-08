package ru.restofleet.agent.data.report

import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.port.CheckResultJournal
import java.util.concurrent.ConcurrentHashMap

/** Журнал в памяти процесса: последний результат по проверке; после перезапуска пуст (отчёт — разовый). */
class InMemoryCheckResultJournal : CheckResultJournal {
    private val results = ConcurrentHashMap<CheckId, CheckResult>()

    override fun record(result: CheckResult) {
        results[result.id] = result
    }

    override fun recorded(): Map<CheckId, CheckResult> = results.toMap()
}
