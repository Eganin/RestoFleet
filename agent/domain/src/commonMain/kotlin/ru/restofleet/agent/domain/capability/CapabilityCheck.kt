package ru.restofleet.agent.domain.capability

/** Одна проверка матрицы осуществимости; реализации живут в data/platform. */
interface CapabilityCheck {
    val id: CheckId
    suspend fun run(): CheckResult
}
