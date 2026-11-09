package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.model.isValidPackageName
import ru.restofleet.agent.domain.port.PayloadEncoder
import ru.restofleet.agent.domain.port.ShadowStore
import ru.restofleet.shared.kernel.Clock

/**
 * Применяет `shadow/desired`: версия старее сохранённой игнорируется, новее — сохраняется, и в обоих «неигнорируемых»
 * случаях публикуется `reported` (повтор той же версии только повторяет `reported`). Невалидные поля не применяются,
 * причины идут в `errors` (коды `telemetryIntervalSec:out_of_range`, `probes:empty_id`, `probes:empty_url:<id>`,
 * `probes:duplicate_id:<id>`, `watchedApps:empty`, `watchedApps:invalid_name:<имя>`). Сохранённую тень циклы
читают сами (интервал телеметрии, цели проб, отслеживаемые пакеты, 3.6b). Сбой хранилища
 * пробрасывается: `reported` о неприменённой версии не публикуется.
 */
class ApplyDesiredShadow(
    private val store: ShadowStore,
    private val publish: PublishOrBuffer,
    private val encoder: PayloadEncoder,
    private val clock: Clock,
) {
    suspend operator fun invoke(d: DesiredShadow) {
        val applied = store.appliedVersion()
        if (applied != null && d.version < applied) return
        val errors = mutableListOf<String>()
        val valid = sanitize(d, errors)
        if (applied == null || d.version > applied) store.save(valid)
        val now = clock.nowMillis()
        publish(
            OutgoingMessage(
                kind = MessageKind.SHADOW_REPORTED,
                payload = encoder.reported(valid, now, errors),
                qos = REPORTED_QOS,
                createdAt = now,
            ),
        )
    }

    private fun sanitize(d: DesiredShadow, errors: MutableList<String>): DesiredShadow {
        val interval = if (d.telemetryIntervalSec in INTERVAL_RANGE_SEC) {
            d.telemetryIntervalSec
        } else {
            errors += "telemetryIntervalSec:out_of_range"
            DEFAULT_INTERVAL_SEC
        }
        return d.copy(
            telemetryIntervalSec = interval,
            watchedApps = validPackages(d.watchedApps, errors),
            probes = validProbes(d.probes, errors),
        )
    }

    private fun validPackages(apps: List<String>, errors: MutableList<String>): List<String> =
        apps.filter { name ->
            when {
                name.isBlank() -> false.also { errors += "watchedApps:empty" }
                !isValidPackageName(name) -> false.also { errors += "watchedApps:invalid_name:$name" }
                else -> true
            }
        }.distinct()

    private fun validProbes(probes: List<ProbeTarget>, errors: MutableList<String>): List<ProbeTarget> {
        val seen = mutableSetOf<String>()
        return probes.filter { probe ->
            when {
                probe.id.isBlank() -> false.also { errors += "probes:empty_id" }
                probe.url.isBlank() -> false.also { errors += "probes:empty_url:${probe.id}" }
                !seen.add(probe.id) -> false.also { errors += "probes:duplicate_id:${probe.id}" }
                else -> true
            }
        }
    }

    private companion object {
        /** ТЗ диапазон не задаёт; решение контроллера 3.4. */
        val INTERVAL_RANGE_SEC = 10..3_600
        const val DEFAULT_INTERVAL_SEC = 30
        const val REPORTED_QOS = 1
    }
}
