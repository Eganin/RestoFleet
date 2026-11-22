package ru.restofleet.console.feature.commands.ui.viewmodel.mapper

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.feature.commands.domain.CommandRecord
import ru.restofleet.console.feature.commands.domain.CommandState
import ru.restofleet.console.feature.commands.domain.CommandType
import ru.restofleet.console.feature.commands.domain.Disabled
import ru.restofleet.console.feature.commands.ui.viewmodel.CommandRowUi
import ru.restofleet.console.feature.commands.ui.viewmodel.CommandsSession
import ru.restofleet.console.feature.commands.ui.viewmodel.CommandsUiState
import ru.restofleet.console.feature.commands.ui.viewmodel.FieldUi
import ru.restofleet.console.feature.commands.ui.viewmodel.SpecUi

class CommandsUiStateMapper {
    fun map(s: CommandsSession): CommandsUiState {
        val missing = (s.sendError as? ConsoleError.InvalidInput)?.field
        return CommandsUiState(
            specs = s.specs.map { SpecUi(it.type, title(it.type), subtitle(it.type), it.enabled, reason(it.disabled)) },
            history = s.history.map(::row),
            loading = s.loading,
            errorText = s.error?.let { text(it) },
            formTitle = s.form?.let(::title),
            fields = s.form?.params.orEmpty().map { FieldUi(it, label(it), s.inputs[it].orEmpty(), it == missing) },
            sending = s.sending,
            sendErrorText = s.sendError?.takeIf { it !is ConsoleError.InvalidInput }?.let { text(it) }
                ?: missing?.let { "Заполните поле «${label(it)}»" },
        )
    }

    private fun row(r: CommandRecord): CommandRowUi {
        val delivered = r.deliveredAt?.let { "delivered ${seconds(it - r.createdAt)}" }
        val done = when (r.state) {
            CommandState.Queued, CommandState.Delivered -> null
            CommandState.Expired -> "expired"
            else -> "${r.state.wire}${r.finishedAt?.let { " " + seconds(it - r.createdAt) }.orEmpty()}"
        }
        val reason = r.reason?.takeIf { r.state == CommandState.Failed || r.state == CommandState.Unsupported }
        val text = listOfNotNull(
            listOfNotNull(delivered, done).joinToString(" → ").takeIf { it.isNotEmpty() } ?: "в очереди",
            reason?.let(::reasonText),
        ).joinToString(" · ")
        return CommandRowUi(
            r.cmdId, r.type, text, r.state.wire,
            when (r.state) {
                CommandState.Succeeded -> StatusTone.Ok
                CommandState.Failed, CommandState.Unsupported -> StatusTone.Crit
                CommandState.Expired -> StatusTone.Warn
                else -> StatusTone.Off
            },
        )
    }

    private fun seconds(ms: Long): String {
        val tenths = (if (ms < 0) 0 else ms) / MS_PER_TENTH
        return "${tenths / TENTH}.${tenths % TENTH} с"
    }

    private fun reasonText(reason: String): String = when (reason) {
        "requires_device_owner" -> "нужен Device Owner"
        "kiosk_active" -> "включён киоск"
        else -> reason
    }

    private fun title(t: CommandType): String = when (t) {
        CommandType.Ping -> "Ping"
        CommandType.ShowMessage -> "Показать сообщение"
        CommandType.CollectDiagnostics -> "Собрать диагностику"
        CommandType.LaunchApp -> "Открыть приложение"
        CommandType.Reboot -> "Перезагрузить"
        CommandType.LockTask -> "Киоск"
        CommandType.InstallUpdate -> "Установить обновление"
    }

    private fun subtitle(t: CommandType): String = when (t) {
        CommandType.Ping -> "ответ со временем устройства"
        CommandType.ShowMessage -> "текст персоналу на экране"
        CommandType.CollectDiagnostics -> "снимок + прогон всех проверок"
        CommandType.LaunchApp -> "открыть приложение по имени пакета"
        CommandType.Reboot -> "устройство перезагрузится через 3 с"
        CommandType.LockTask -> "закрепить приложение на экране"
        CommandType.InstallUpdate -> "тихая установка APK"
    }

    private fun reason(d: Disabled?): String? = when (d) {
        null -> null
        Disabled.NeedsDeviceOwner -> "Нужен режим Device Owner"
        Disabled.NotInThisStage -> "Параметры команды недоступны в консоли (диплом)"
    }

    private fun label(key: String): String = when (key) {
        "text" -> "Текст сообщения"
        "pkg" -> "Пакет приложения"
        "package" -> "Пакет"
        "url" -> "Адрес APK"
        "sha256" -> "SHA-256"
        else -> key
    }

    private fun text(e: ConsoleError): String = when (e) {
        ConsoleError.Network -> "Нет связи с сервером"
        ConsoleError.Forbidden -> "Недостаточно прав"
        ConsoleError.NotFound -> "Устройство не найдено"
        is ConsoleError.Conflict -> "Устройство не принимает команду: ${e.reason ?: "конфликт"}"
        is ConsoleError.Validation -> "Сервер отклонил параметры: ${e.message}"
        else -> "Не удалось выполнить"
    }

    private companion object {
        const val MS_PER_TENTH = 100L
        const val TENTH = 10L
    }
}
