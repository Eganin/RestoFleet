package ru.restofleet.server.domain.health

/** Проверка зависимости для `GET /health` (FR-BE-56): БД, брокер. */
interface HealthProbe {
    val name: String

    /** Быстрая проверка без исключений: `false` — зависимость недоступна. */
    suspend fun isHealthy(): Boolean
}

/** Итог `GET /health`: по компоненту на проверку. */
data class HealthReport(val components: Map<String, Boolean>) {
    val healthy: Boolean get() = components.values.all { it }
}
