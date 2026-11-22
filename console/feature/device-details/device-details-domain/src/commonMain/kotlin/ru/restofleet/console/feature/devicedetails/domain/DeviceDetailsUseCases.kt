package ru.restofleet.console.feature.devicedetails.domain

import kotlinx.coroutines.flow.Flow
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

class LoadDeviceCard(private val repository: DeviceDetailsRepository) {
    suspend operator fun invoke(deviceId: String): Outcome<DeviceCard, ConsoleError> = repository.card(deviceId)
}

/** График температуры за последние 30 минут (по возрастанию времени). */
class LoadTemperatureHistory(private val repository: DeviceDetailsRepository, private val clock: Clock) {
    suspend operator fun invoke(deviceId: String): Outcome<List<TempPoint>, ConsoleError> =
        repository.temperature(deviceId, clock.nowMillis() - WINDOW_MS, LIMIT)

    companion object {
        const val WINDOW_MS = 30 * 60_000L
        const val LIMIT = 200
    }
}

/** Последние проверки: [LIMIT] штук, новые сначала. */
class LoadProbes(private val repository: DeviceDetailsRepository) {
    suspend operator fun invoke(deviceId: String): Outcome<List<ProbeResult>, ConsoleError> =
        repository.probes(deviceId, LIMIT)

    companion object {
        const val LIMIT = 30
    }
}

class LoadEvents(private val repository: DeviceDetailsRepository) {
    suspend operator fun invoke(deviceId: String, offset: Int): Outcome<EventsPage, ConsoleError> =
        repository.events(deviceId, offset, PAGE_SIZE)

    companion object {
        const val PAGE_SIZE = 20
    }
}

class ObserveDeviceLive(private val repository: DeviceDetailsRepository) {
    operator fun invoke(deviceId: String): Flow<DeviceLiveEvent> = repository.live(deviceId)
}

/** Порог температуры для линии на графике: из условия тревоги R-03, иначе умолчание агента. */
object TempThreshold {
    const val DEFAULT = 45.0

    fun of(alerts: List<DeviceAlert>): Double =
        alerts.firstOrNull { it.ruleId == "R-03" }?.details?.get("limit")?.toDoubleOrNull() ?: DEFAULT
}
