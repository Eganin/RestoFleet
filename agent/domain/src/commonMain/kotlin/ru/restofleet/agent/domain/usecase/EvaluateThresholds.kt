package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.model.Thresholds
import ru.restofleet.agent.domain.port.ShadowStore
import ru.restofleet.agent.domain.threshold.ForegroundTracker
import ru.restofleet.agent.domain.threshold.ThresholdTracker

/**
 * Проверка порогов и переднего плана по снимку телеметрии (FR-AG-40, 41; реш. 202, 218, 220): вызывается циклом
 * телеметрии после `CollectTelemetry`. Пороги и отслеживаемые приложения — из применённой тени (без тени — значения по
 * умолчанию [Thresholds], без отслеживаемых приложений). События `threshold`, `fg_changed`, `fg_absent` уходят сразу
 * через [publishEvent] (без связи — в outbox). Состояние зон и эпизодов — в памяти (план 3.3: после перезапуска
 * агента первое пересечение шлётся заново).
 */
class EvaluateThresholds(
    private val shadow: ShadowStore,
    private val publishEvent: PublishEvent,
) {
    private val mutex = Mutex()
    private val thresholds = ThresholdTracker()
    private val foreground = ForegroundTracker()

    suspend operator fun invoke(snapshot: TelemetrySnapshot) {
        val desired = shadow.load()
        val limits = desired?.thresholds ?: Thresholds()
        val watched = desired?.watchedApps.orEmpty()
        val events = mutex.withLock {
            thresholds.evaluate(snapshot, limits) +
                foreground.evaluate(snapshot.ts, snapshot.foregroundApp, watched, limits.fgAbsentMin)
        }
        events.forEach { publishEvent(it) }
    }
}
