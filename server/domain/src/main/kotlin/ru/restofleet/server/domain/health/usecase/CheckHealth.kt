package ru.restofleet.server.domain.health.usecase

import ru.restofleet.server.domain.health.HealthProbe
import ru.restofleet.server.domain.health.HealthReport

/** `GET /health`: опрос всех [HealthProbe]; сбой проверки — компонент нездоров. */
class CheckHealth(private val probes: List<HealthProbe>) {
    suspend operator fun invoke(): HealthReport = HealthReport(
        probes.associate { probe ->
            probe.name to try {
                probe.isHealthy()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                false
            }
        },
    )
}
