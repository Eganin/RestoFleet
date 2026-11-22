package ru.restofleet.server.domain.incident

import ru.restofleet.server.domain.ingest.ProbeReport
import ru.restofleet.shared.kernel.DeviceId

/**
 * Состояние «устройство × цель» для агрегатора (реш. 411, таблица `probe_states`): автомат K агента (`probe_state`)
 * и время последнего результата пробы — свежесть окна. Только в транзакции.
 */
interface ProbeStateRepository {
    /** `probe_state` агента: [down] с момента [at]; [error] — класс ошибки (у `down`). */
    suspend fun recordState(deviceId: DeviceId, target: String, down: Boolean, error: String?, at: Long)

    /** Время последнего результата по каждой цели отчётов; новая пара «устройство × цель» начинает с `up`. */
    suspend fun recordResults(reports: List<ProbeReport>)

    /** Пары с результатом не раньше [since] (окно агрегатора). */
    suspend fun fresh(since: Long): List<ProbeObservation>
}
