package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.port.PayloadEncoder
import ru.restofleet.agent.domain.port.ShadowStore
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.shadow.DesiredLimits
import ru.restofleet.shared.shadow.VersionDecision
import ru.restofleet.shared.shadow.validateDesired

/**
 * Применяет `shadow/desired`: решение по версии и валидация — общие из `shared:shadow` ([VersionDecision],
 * [validateDesired], реш. 225). Версия старее сохранённой игнорируется, новее — сохраняется, и в обоих «неигнорируемых»
 * случаях публикуется `reported` (повтор той же версии только повторяет `reported`). Невалидные поля не применяются:
 * интервал вне диапазона оставляет прежний применённый (без прежней тени — 30 с), причины идут в `errors`. Сохранённую
 * тень циклы читают сами (интервал телеметрии, цели проб, отслеживаемые пакеты, 3.6b). Сбой хранилища пробрасывается:
 * `reported` о неприменённой версии не публикуется.
 */
class ApplyDesiredShadow(
    private val store: ShadowStore,
    private val publish: PublishOrBuffer,
    private val encoder: PayloadEncoder,
    private val clock: Clock,
) {
    suspend operator fun invoke(d: DesiredShadow) {
        val decision = VersionDecision.of(store.appliedVersion(), d.version)
        if (decision == VersionDecision.IGNORE_OLDER) return
        val check = validateDesired(d.telemetryIntervalSec, d.watchedApps, d.probes, ProbeTarget::id, ProbeTarget::url)
        // Неверный интервал поле не меняет: берётся интервал прежней тени (Ruling 279, решение 177)
        val interval = check.telemetryIntervalSec
            ?: store.load()?.telemetryIntervalSec
            ?: DesiredLimits.DEFAULT_TELEMETRY_INTERVAL_SEC
        val valid = d.copy(telemetryIntervalSec = interval, watchedApps = check.watchedApps, probes = check.probes)
        if (decision == VersionDecision.APPLY_NEWER) store.save(valid)
        val now = clock.nowMillis()
        publish(
            OutgoingMessage(
                kind = MessageKind.SHADOW_REPORTED,
                payload = encoder.reported(valid, now, check.errors),
                qos = REPORTED_QOS,
                createdAt = now,
            ),
        )
    }

    private companion object {
        const val REPORTED_QOS = 1
    }
}
