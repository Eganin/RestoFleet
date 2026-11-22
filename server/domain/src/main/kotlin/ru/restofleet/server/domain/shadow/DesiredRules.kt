package ru.restofleet.server.domain.shadow

import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowKeys
import ru.restofleet.shared.shadow.ShadowValue
import ru.restofleet.shared.shadow.validateDesired

/** `desired` нового устройства (ТЗ 7.2): интервал 30 с, пороги R-02…R-04, без отслеживаемых приложений и проб. */
object DefaultDesired {
    const val TELEMETRY_INTERVAL_SEC = 30
    const val BATTERY_LOW_PCT = 20
    const val TEMP_HIGH_C = 45
    const val FG_ABSENT_MIN = 10

    val document: ShadowDocument = ShadowDocument(
        mapOf(
            "telemetry" to obj("intervalSec" to ShadowValue.Num(TELEMETRY_INTERVAL_SEC)),
            "thresholds" to obj(
                "batteryLowPct" to ShadowValue.Num(BATTERY_LOW_PCT),
                "tempHighC" to ShadowValue.Num(TEMP_HIGH_C),
                "fgAbsentMin" to ShadowValue.Num(FG_ABSENT_MIN),
            ),
            "watchedApps" to ShadowValue.List(emptyList()),
            "probes" to ShadowValue.List(emptyList()),
        ),
    )

    private fun obj(vararg pairs: Pair<String, ShadowValue>) = ShadowValue.Obj(mapOf(*pairs))
}

/**
 * Изменение `desired` (реш. 237): тело `PUT /shadow` накладывается на текущий `desired` как merge-patch — объекты
 * сливаются рекурсивно, списки и листья заменяются целиком; служебные ключи корня отбрасываются (версией управляет
 * бэкенд).
 */
object DesiredMerge {
    fun merge(base: ShadowDocument, patch: ShadowDocument): ShadowDocument =
        ShadowDocument(mergeFields(base.fields, patch.fields - ShadowKeys.METADATA))

    private fun mergeFields(base: Map<String, ShadowValue>, patch: Map<String, ShadowValue>): Map<String, ShadowValue> =
        base + patch.mapValues { (key, value) ->
            val old = base[key]
            if (old is ShadowValue.Obj && value is ShadowValue.Obj) {
                ShadowValue.Obj(mergeFields(old.fields, value.fields))
            } else {
                value
            }
        }
}

/**
 * Схема `desired` (реш. 237): только известные ключи и типы `ShadowDesiredDto` (иначе агент не разберёт тень, а `delta`
 * никогда не опустеет), пределы ниже и правила `validateDesired` из `shared:shadow` (коды ТЗ 7.2). Возвращает первое
 * нарушение: `field` — путь (`probes[1].url`), `reason` — код.
 */
object DesiredRules {
    private val ROOT = setOf("telemetry", "thresholds", "watchedApps", "probes")
    private val TELEMETRY_KEYS = setOf("intervalSec")
    private val THRESHOLD_KEYS = setOf("batteryLowPct", "tempHighC", "fgAbsentMin")
    private val PROBE_REQUIRED = listOf("id", "type", "url", "intervalSec", "timeoutMs", "failThreshold")
    private val PROBE_KEYS = PROBE_REQUIRED.toSet() + setOf("reference", "expectedStatus")
    private val PROBE_TYPES = setOf("http", "tcp")

    private val BATTERY_LOW_PCT = 1..100
    private val TEMP_HIGH_C = 0.0..100.0
    private val FG_ABSENT_MIN = 1..1_440
    private val PROBE_INTERVAL_SEC = 5..3_600
    private val PROBE_TIMEOUT_MS = 100..60_000
    private val PROBE_FAIL_THRESHOLD = 1..100
    private val HTTP_STATUS = 100..599

    fun validate(desired: ShadowDocument): ServerError.Validation? = try {
        check(desired)
        null
    } catch (e: Invalid) {
        e.error
    }

    private fun check(desired: ShadowDocument) {
        knownKeys(desired.fields, ROOT, "")
        val telemetry = obj(desired.fields, "telemetry", "")
        knownKeys(telemetry.fields, TELEMETRY_KEYS, "telemetry.")
        val interval = int(telemetry.fields, "intervalSec", "telemetry.")
        val thresholds = obj(desired.fields, "thresholds", "")
        knownKeys(thresholds.fields, THRESHOLD_KEYS, "thresholds.")
        int(thresholds.fields, "batteryLowPct", "thresholds.", BATTERY_LOW_PCT)
        val temp = num(thresholds.fields, "tempHighC", "thresholds.")
        if (temp !in TEMP_HIGH_C) fail("thresholds.tempHighC", "out_of_range")
        int(thresholds.fields, "fgAbsentMin", "thresholds.", FG_ABSENT_MIN)
        val apps = list(desired.fields, "watchedApps").mapIndexed { i, item ->
            (item as? ShadowValue.Str)?.value ?: fail("watchedApps[$i]", "type")
        }
        val probes = list(desired.fields, "probes").mapIndexed { i, item -> probe(item, "probes[$i]") }
        val shared = validateDesired(interval, apps, probes, { it.first }, { it.second })
        shared.errors.firstOrNull()?.let { fail(it.substringBefore(':'), it) }
    }

    /** Пара (id, url) цели — для общих правил `validateDesired`. */
    private fun probe(item: ShadowValue, path: String): Pair<String, String> {
        val probe = (item as? ShadowValue.Obj)?.fields ?: fail(path, "type")
        knownKeys(probe, PROBE_KEYS, "$path.")
        PROBE_REQUIRED.firstOrNull { it !in probe }?.let { fail("$path.$it", "required") }
        val id = str(probe, "id", "$path.")
        if (str(probe, "type", "$path.") !in PROBE_TYPES) fail("$path.type", "unknown_type")
        val url = str(probe, "url", "$path.")
        int(probe, "intervalSec", "$path.", PROBE_INTERVAL_SEC)
        int(probe, "timeoutMs", "$path.", PROBE_TIMEOUT_MS)
        int(probe, "failThreshold", "$path.", PROBE_FAIL_THRESHOLD)
        probe["reference"]?.let { if (it !is ShadowValue.Bool) fail("$path.reference", "type") }
        if ("expectedStatus" in probe) int(probe, "expectedStatus", "$path.", HTTP_STATUS)
        return id to url
    }

    private fun knownKeys(fields: Map<String, ShadowValue>, allowed: Set<String>, prefix: String) {
        fields.keys.firstOrNull { it !in allowed }?.let { fail("$prefix$it", "unknown_field") }
    }

    private fun obj(fields: Map<String, ShadowValue>, key: String, prefix: String): ShadowValue.Obj =
        when (val v = fields[key]) {
            null -> fail("$prefix$key", "required")
            is ShadowValue.Obj -> v
            else -> fail("$prefix$key", "type")
        }

    private fun list(fields: Map<String, ShadowValue>, key: String): List<ShadowValue> = when (val v = fields[key]) {
        null -> fail(key, "required")
        is ShadowValue.List -> v.items
        else -> fail(key, "type")
    }

    private fun str(fields: Map<String, ShadowValue>, key: String, prefix: String): String =
        (fields[key] as? ShadowValue.Str)?.value ?: fail("$prefix$key", "type")

    private fun num(fields: Map<String, ShadowValue>, key: String, prefix: String): Double =
        when (val v = fields[key]) {
            null -> fail("$prefix$key", "required")
            is ShadowValue.Num -> v.value
            else -> fail("$prefix$key", "type")
        }

    private fun int(fields: Map<String, ShadowValue>, key: String, prefix: String, range: IntRange? = null): Int {
        val v = num(fields, key, prefix)
        if (v % 1.0 != 0.0 || v < Int.MIN_VALUE || v > Int.MAX_VALUE) fail("$prefix$key", "not_integer")
        val i = v.toInt()
        if (range != null && i !in range) fail("$prefix$key", "out_of_range")
        return i
    }

    private fun fail(field: String, reason: String): Nothing = throw Invalid(ServerError.Validation(field, reason))

    /** Короткое замыкание проверки: без стека, наружу не выходит. */
    private class Invalid(val error: ServerError.Validation) : RuntimeException(null, null, false, false)
}
