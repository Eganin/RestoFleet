package ru.restofleet.agent.domain.capability

/** Результат одной проверки; [raw] — сырые значения для отчёта, [note] — пояснение, [checkedAt] — epoch millis. */
data class CheckResult(
    val id: CheckId,
    val status: CheckStatus,
    val raw: Map<String, String>,
    val note: String?,
    val checkedAt: Long,
)
