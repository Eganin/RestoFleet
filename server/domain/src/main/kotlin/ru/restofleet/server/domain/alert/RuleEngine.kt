package ru.restofleet.server.domain.alert

import ru.restofleet.server.domain.ingest.DeviceSignal
import ru.restofleet.shared.kernel.DeviceId

/** Вход правил: смена статуса, точка телеметрии или событие устройства (план 2.1). */
sealed interface RuleInput {
    val deviceId: DeviceId
    val ts: Long

    /** Применённый `status` (чужой `connId` отсекает ещё `HandleStatus`). */
    data class StatusChanged(
        override val deviceId: DeviceId,
        override val ts: Long,
        val online: Boolean,
        val reason: String? = null,
    ) : RuleInput

    data class Telemetry(
        override val deviceId: DeviceId,
        override val ts: Long,
        val charging: Boolean?,
        val freeMb: Long?,
    ) : RuleInput

    data class Event(override val deviceId: DeviceId, override val ts: Long, val signal: DeviceSignal) : RuleInput
}

/** Решение правил о тревоге. */
sealed interface AlertChange {
    data class Open(val subject: AlertSubject, val details: Map<String, String>, val at: Long) : AlertChange

    /** Закрыть автоматически (`auto = true`): условие перестало выполняться (реш. 413). */
    data class AutoResolve(val alertId: String, val at: Long) : AlertChange
}

/** Новые действующие факты-условия и решения о тревогах. */
data class RuleOutcome(val conditions: Map<ConditionKey, Condition>, val changes: List<AlertChange>)

/**
 * Правила R-01…R-06 (FR-BE-30, 31; реш. 413, 415) — чистая функция. Вход меняет факты-условия; тревога открывается,
 * когда условие правила **начинает** выполняться, и закрывается автоматически, когда перестаёт:
 * - повторный вход при действующем условии ничего не меняет (повторы событий после перезапуска агента);
 * - ручное закрытие при сохранившемся условии не открывает тревогу снова — нужен выход и новый вход.
 *
 * Условия: R-01 — офлайн; R-02 — `battery_low` и не на зарядке (по последней телеметрии); R-03 — `temp_high` или
 * `thermal`; R-04 — `fg_absent` пакета до `fg_changed` на него; R-05 — `probe_state down` цели до `up`; R-06 —
 * свободно меньше [RuleParams.freeMbEnter] МБ до ≥ [RuleParams.freeMbExit] (гистерезис).
 */
object RuleEngine {
    fun evaluate(
        input: RuleInput,
        conditions: Map<ConditionKey, Condition>,
        open: List<Alert>,
        params: RuleParams = RuleParams(),
    ): RuleOutcome {
        val facts = facts(input, params)
        if (facts.isEmpty()) return RuleOutcome(conditions, emptyList())
        val next = conditions.toMutableMap()
        facts.forEach { fact ->
            when {
                !fact.active -> next.remove(fact.key)
                fact.key !in next -> next[fact.key] = Condition(fact.key, input.ts, fact.details)
            }
        }
        val changes = facts.map { it.key.subject }.distinct().mapNotNull { subject ->
            val before = holds(subject, conditions)
            val after = holds(subject, next)
            val existing = open.firstOrNull { it.isActive && it.subject == subject }
            when {
                after && !before && existing == null -> AlertChange.Open(subject, details(subject, next), input.ts)
                !after && existing != null -> AlertChange.AutoResolve(existing.id, input.ts)
                else -> null
            }
        }
        return RuleOutcome(next, changes)
    }

    private data class Fact(val key: ConditionKey, val active: Boolean, val details: Map<String, String> = emptyMap())

    private fun facts(input: RuleInput, params: RuleParams): List<Fact> {
        val id = input.deviceId
        return when (input) {
            is RuleInput.StatusChanged -> listOf(
                Fact(key(RuleId.R01, id, facet = Facets.OFFLINE), !input.online, details("reason" to input.reason)),
            )
            is RuleInput.Telemetry -> telemetryFacts(input, params)
            is RuleInput.Event -> signalFacts(id, input.signal)
        }
    }

    private fun telemetryFacts(input: RuleInput.Telemetry, params: RuleParams): List<Fact> = buildList {
        val id = input.deviceId
        input.charging?.let { add(Fact(key(RuleId.R02, id, facet = Facets.CHARGING), it)) }
        input.freeMb?.let { free ->
            val low = key(RuleId.R06, id, facet = Facets.LOW_STORAGE)
            val details = details("freeMb" to free.toString(), "limit" to params.freeMbEnter.toString())
            when {
                free < params.freeMbEnter -> add(Fact(low, true, details))
                free >= params.freeMbExit -> add(Fact(low, false))
            }
        }
    }

    private fun signalFacts(id: DeviceId, signal: DeviceSignal): List<Fact> = when (signal) {
        is DeviceSignal.Threshold -> thresholdFact(id, signal)?.let(::listOf).orEmpty()
        is DeviceSignal.FgAbsent -> listOf(
            Fact(
                key(RuleId.R04, id, signal.pkg, Facets.FG_ABSENT),
                true,
                details(
                    "pkg" to signal.pkg, "foregroundApp" to signal.foregroundApp,
                    "sinceTs" to signal.sinceTs.toString(), "limitMin" to signal.limitMin.toString(),
                ),
            ),
        )
        is DeviceSignal.FgChanged ->
            signal.to?.let { listOf(Fact(key(RuleId.R04, id, it, Facets.FG_ABSENT), false)) }.orEmpty()
        is DeviceSignal.ProbeState -> listOf(
            Fact(
                key(RuleId.R05, id, signal.target, Facets.TARGET_DOWN),
                signal.down,
                details("error" to signal.error, "failures" to signal.failures.toString()),
            ),
        )
    }

    private fun thresholdFact(id: DeviceId, signal: DeviceSignal.Threshold): Fact? {
        val limit = number(signal.limit)
        return when (signal.metric) {
            DeviceSignal.BATTERY_LOW -> Fact(
                key(RuleId.R02, id, facet = Facets.BATTERY_LOW),
                signal.enter,
                details("batteryLevel" to number(signal.value), "limit" to limit),
            )
            DeviceSignal.TEMP_HIGH -> Fact(
                key(RuleId.R03, id, facet = Facets.TEMP_HIGH),
                signal.enter,
                details("tempC" to signal.value.toString(), "limit" to signal.limit.toString()),
            )
            DeviceSignal.THERMAL -> Fact(
                key(RuleId.R03, id, facet = Facets.THERMAL),
                signal.enter,
                details("thermal" to thermalName(signal.value), "limit" to thermalName(signal.limit)),
            )
            else -> null
        }
    }

    /** Выполняется ли условие правила субъекта при этих фактах. */
    private fun holds(subject: AlertSubject, conditions: Map<ConditionKey, Condition>): Boolean {
        val facets = conditions.keys.filter { it.subject == subject }.map { it.facet }.toSet()
        return when (subject.rule) {
            RuleId.R02 -> Facets.BATTERY_LOW in facets && Facets.CHARGING !in facets
            else -> facets.isNotEmpty()
        }
    }

    /** Значения условия для новой тревоги: из всех действующих фактов субъекта (без служебного `charging`). */
    private fun details(subject: AlertSubject, conditions: Map<ConditionKey, Condition>): Map<String, String> =
        conditions.values.filter { it.key.subject == subject && it.key.facet != Facets.CHARGING }
            .sortedBy { it.key.facet }
            .fold(emptyMap()) { acc, c -> acc + c.details }

    private fun key(rule: RuleId, id: DeviceId, target: String? = null, facet: String) =
        ConditionKey(rule, id, target, facet)

    private fun details(vararg pairs: Pair<String, String?>): Map<String, String> =
        pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    /** `19.0` → `19`, `18.5` → `18.5`. */
    private fun number(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()

    /** Код теплового статуса Android (`PowerManager.THERMAL_STATUS_*`) → имя, как шлёт агент в телеметрии. */
    private fun thermalName(code: Double): String =
        THERMAL_NAMES.getOrNull(code.toInt()).takeIf { code == Math.floor(code) } ?: number(code)

    private val THERMAL_NAMES = listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
}

/** Имена фактов-условий (столбец `facet`). */
object Facets {
    const val OFFLINE = "offline"
    const val BATTERY_LOW = "battery_low"
    const val CHARGING = "charging"
    const val TEMP_HIGH = "temp_high"
    const val THERMAL = "thermal"
    const val FG_ABSENT = "fg_absent"
    const val TARGET_DOWN = "down"
    const val LOW_STORAGE = "low_storage"
}
