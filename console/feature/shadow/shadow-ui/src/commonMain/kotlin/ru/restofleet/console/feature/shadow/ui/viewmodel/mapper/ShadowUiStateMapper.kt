package ru.restofleet.console.feature.shadow.ui.viewmodel.mapper

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.common.RelativeTime
import ru.restofleet.console.feature.shadow.domain.FlattenShadow
import ru.restofleet.console.feature.shadow.domain.ShadowErrors
import ru.restofleet.console.feature.shadow.domain.ShadowFields
import ru.restofleet.console.feature.shadow.domain.ShadowRow
import ru.restofleet.console.feature.shadow.domain.ShadowState
import ru.restofleet.console.feature.shadow.ui.viewmodel.FormFieldUi
import ru.restofleet.console.feature.shadow.ui.viewmodel.Notice
import ru.restofleet.console.feature.shadow.ui.viewmodel.ShadowMode
import ru.restofleet.console.feature.shadow.ui.viewmodel.ShadowRowUi
import ru.restofleet.console.feature.shadow.ui.viewmodel.ShadowSession
import ru.restofleet.console.feature.shadow.ui.viewmodel.ShadowUiState

class ShadowUiStateMapper(private val flatten: FlattenShadow = FlattenShadow()) {
    fun map(s: ShadowSession): ShadowUiState {
        val shadow = s.state
        val form = s.form
        val jsonError = (s.saveError as? ConsoleError.InvalidInput)?.takeIf { it.field == "json" }
        return ShadowUiState(
            loading = s.loading,
            errorText = s.error?.let { text(it) },
            header = shadow?.let(::header).orEmpty(),
            banner = shadow?.let(::banner).orEmpty(),
            bannerPending = shadow?.pending == true,
            deviceErrors = shadow?.errors?.takeIf { it.isNotEmpty() }?.joinToString("; ")?.let { "Устройство сообщило: $it" },
            rows = shadow?.let { flatten(it).map(::row) }.orEmpty(),
            mode = s.mode,
            fields = form?.let {
                listOf(
                    field(ShadowFields.INTERVAL, "Интервал телеметрии, с", it.intervalSec, s),
                    field(ShadowFields.BATTERY, "Низкий заряд, %", it.batteryLowPct, s),
                    field(ShadowFields.TEMP, "Перегрев, °C", it.tempHighC, s),
                    field(ShadowFields.FG_ABSENT, "Приложение не на экране, мин", it.fgAbsentMin, s),
                )
            }.orEmpty(),
            apps = form?.watchedApps.orEmpty(),
            appsError = s.fieldErrors[ShadowFields.APPS]?.let(::errorText),
            jsonText = s.jsonText,
            jsonError = jsonError?.let { "Некорректный JSON: ${it.message}" },
            saving = s.saving,
            saveErrorText = s.saveError?.takeIf { jsonError == null }?.let { text(it) },
            noticeText = s.notice?.let(::notice),
            canEdit = shadow != null && s.mode == ShadowMode.View,
        )
    }

    private fun header(s: ShadowState): String {
        val reported = s.reportedVersion?.let { "reported v$it" } ?: "reported —"
        val delta = if (s.pending) "delta · ${RelativeTime.ago(s.loadedAt, s.desiredUpdatedAt).removeSuffix(" назад")}" else "delta пуста"
        return "desired v${s.desiredVersion} · $reported · $delta"
    }

    private fun banner(s: ShadowState): String =
        if (s.pending) {
            "Ожидает применения: устройство получит v${s.desiredVersion} при следующей синхронизации"
        } else {
            "Применено на устройстве"
        }

    private fun row(r: ShadowRow) = ShadowRowUi(
        r.path,
        if (r.changed && r.reported != null) "${r.reported} → ${r.desired}" else r.desired,
        r.changed,
    )

    private fun field(key: String, label: String, value: String, s: ShadowSession) =
        FormFieldUi(key, label, value, s.fieldErrors[key]?.let(::errorText))

    private fun errorText(code: String): String = when (code) {
        ShadowErrors.NOT_A_NUMBER -> "Нужно число"
        ShadowErrors.OUT_OF_RANGE -> "Вне допустимого диапазона"
        ShadowErrors.INVALID_APP -> "Неверное имя пакета"
        else -> code
    }

    private fun notice(n: Notice): String = when (n) {
        Notice.Saved -> "Сохранено: устройство получит новое значение"
        Notice.SavedNotPublished -> "Сохранено, но брокер недоступен: устройство получит при подключении"
        Notice.NoChanges -> "Изменений нет"
    }

    private fun text(e: ConsoleError): String = when (e) {
        ConsoleError.Network -> "Нет связи с сервером"
        ConsoleError.NotFound -> "Устройство не найдено"
        ConsoleError.Forbidden -> "Недостаточно прав"
        is ConsoleError.Validation -> "Сервер отклонил значение${e.field?.let { " ($it)" }.orEmpty()}: ${e.message}"
        is ConsoleError.Conflict -> "Устройство выведено из эксплуатации"
        else -> "Не удалось выполнить"
    }
}
