package ru.restofleet.console.feature.shadow.domain

import ru.restofleet.shared.shadow.DesiredErrors
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowKeys
import ru.restofleet.shared.shadow.ShadowValue
import ru.restofleet.shared.shadow.validateDesired

/** Имена полей формы — они же ключи ошибок. */
object ShadowFields {
    const val INTERVAL = "telemetry.intervalSec"
    const val BATTERY = "thresholds.batteryLowPct"
    const val TEMP = "thresholds.tempHighC"
    const val FG_ABSENT = "thresholds.fgAbsentMin"
    const val APPS = "watchedApps"
}

object ShadowErrors {
    const val NOT_A_NUMBER = "not_a_number"
    const val OUT_OF_RANGE = "out_of_range"
    const val INVALID_APP = "invalid_app"
    const val NO_CHANGES = "no_changes"
}

/** Поля формы — текст, чтобы можно было показать неверный ввод до отправки. */
data class ShadowForm(
    val intervalSec: String,
    val batteryLowPct: String,
    val tempHighC: String,
    val fgAbsentMin: String,
    val watchedApps: List<String>,
) {
    companion object {
        /** Форма из текущего desired; чего нет в тени — пустая строка. */
        fun from(desired: ShadowDocument): ShadowForm = ShadowForm(
            intervalSec = number(desired, "telemetry", "intervalSec"),
            batteryLowPct = number(desired, "thresholds", "batteryLowPct"),
            tempHighC = number(desired, "thresholds", "tempHighC"),
            fgAbsentMin = number(desired, "thresholds", "fgAbsentMin"),
            watchedApps = (desired["watchedApps"] as? ShadowValue.List)?.items.orEmpty()
                .mapNotNull { (it as? ShadowValue.Str)?.value },
        )

        private fun number(doc: ShadowDocument, group: String, key: String): String {
            val v = ((doc[group] as? ShadowValue.Obj)?.fields?.get(key) as? ShadowValue.Num)?.value ?: return ""
            return if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
        }
    }
}

/** Желаемое без служебных ключей (`version`, `appliedVersion` …): то, что имеет смысл править. */
object EditableDesired {
    operator fun invoke(desired: ShadowDocument): ShadowDocument =
        ShadowDocument(desired.fields.filterKeys { it !in ShadowKeys.METADATA })
}

/** Проверка формы до `PUT`: интервал и приложения — общей `validateDesired`, пороги — свои границы. */
class ValidateShadowForm {
    operator fun invoke(form: ShadowForm): Map<String, String> {
        val errors = linkedMapOf<String, String>()
        val interval = form.intervalSec.trim().toIntOrNull()
        val validation = validateDesired(interval ?: 0, form.watchedApps.map { it.trim() }, emptyList<Unit>(), { "" }, { "" })
        when {
            form.intervalSec.isBlank() -> Unit
            interval == null -> errors[ShadowFields.INTERVAL] = ShadowErrors.NOT_A_NUMBER
            DesiredErrors.INTERVAL_OUT_OF_RANGE in validation.errors -> errors[ShadowFields.INTERVAL] = ShadowErrors.OUT_OF_RANGE
        }
        if (validation.errors.any { it.startsWith("watchedApps:") }) errors[ShadowFields.APPS] = ShadowErrors.INVALID_APP
        threshold(form.batteryLowPct, BATTERY_RANGE, ShadowFields.BATTERY, errors)
        threshold(form.tempHighC, TEMP_RANGE, ShadowFields.TEMP, errors)
        threshold(form.fgAbsentMin, FG_RANGE, ShadowFields.FG_ABSENT, errors)
        return errors
    }

    private fun threshold(raw: String, range: ClosedFloatingPointRange<Double>, field: String, out: MutableMap<String, String>) {
        if (raw.isBlank()) return
        val v = raw.trim().toDoubleOrNull()
        when {
            v == null -> out[field] = ShadowErrors.NOT_A_NUMBER
            v !in range -> out[field] = ShadowErrors.OUT_OF_RANGE
        }
    }

    companion object {
        val BATTERY_RANGE = 1.0..99.0
        val TEMP_RANGE = 30.0..90.0
        val FG_RANGE = 1.0..180.0
    }
}

sealed interface PatchResult {
    data class Ready(val patch: ShadowDocument) : PatchResult

    data object NoChanges : PatchResult

    data class Invalid(val errors: Map<String, String>) : PatchResult
}

/** Собирает merge-patch только из изменённых полей формы. */
class BuildDesiredPatch(private val validate: ValidateShadowForm) {
    operator fun invoke(original: ShadowDocument, form: ShadowForm): PatchResult {
        val errors = validate(form)
        if (errors.isNotEmpty()) return PatchResult.Invalid(errors)
        val before = ShadowForm.from(original)
        val groups = linkedMapOf<String, MutableMap<String, ShadowValue>>()
        fun changed(group: String, key: String, now: String, was: String) {
            val v = now.trim().toDoubleOrNull()
            if (now.isNotBlank() && now.trim() != was) groups.getOrPut(group) { linkedMapOf() }[key] = ShadowValue.Num(checkNotNull(v))
        }
        changed("telemetry", "intervalSec", form.intervalSec, before.intervalSec)
        changed("thresholds", "batteryLowPct", form.batteryLowPct, before.batteryLowPct)
        changed("thresholds", "tempHighC", form.tempHighC, before.tempHighC)
        changed("thresholds", "fgAbsentMin", form.fgAbsentMin, before.fgAbsentMin)
        val fields = linkedMapOf<String, ShadowValue>()
        groups.forEach { (g, v) -> fields[g] = ShadowValue.Obj(v) }
        val apps = form.watchedApps.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (apps != before.watchedApps) fields["watchedApps"] = ShadowValue.List(apps.map { ShadowValue.Str(it) })
        return if (fields.isEmpty()) PatchResult.NoChanges else PatchResult.Ready(ShadowDocument(fields))
    }
}
