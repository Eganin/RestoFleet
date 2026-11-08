package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult

/**
 * Журнал результатов проверок, которые исполняются руками (команды C1–C4, D2–D6): они меняют состояние устройства,
 * поэтому `RunCapabilityChecks` их не запускает, а отчёт Lab берёт их отсюда. Хранит последний результат по [CheckId].
 */
interface CheckResultJournal {
    fun record(result: CheckResult)

    fun recorded(): Map<CheckId, CheckResult>
}
