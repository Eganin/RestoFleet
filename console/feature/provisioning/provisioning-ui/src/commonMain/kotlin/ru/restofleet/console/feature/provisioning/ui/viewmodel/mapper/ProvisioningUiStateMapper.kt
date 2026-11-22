package ru.restofleet.console.feature.provisioning.ui.viewmodel.mapper

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.common.RelativeTime
import ru.restofleet.console.feature.provisioning.domain.DeviceRole
import ru.restofleet.console.feature.provisioning.domain.IssueCode
import ru.restofleet.console.feature.provisioning.ui.viewmodel.ChoiceUi
import ru.restofleet.console.feature.provisioning.ui.viewmodel.ProvisioningSession
import ru.restofleet.console.feature.provisioning.ui.viewmodel.ProvisioningUiState

class ProvisioningUiStateMapper {
    fun map(s: ProvisioningSession): ProvisioningUiState {
        val issued = s.issued
        return ProvisioningUiState(
            restaurants = s.restaurants.map { ChoiceUi(it.id, it.label, it.id == s.restaurantId) },
            roles = DeviceRole.entries.map { ChoiceUi(it.wire, roleTitle(it), it == s.role) },
            name = s.name,
            issuing = s.issuing,
            canIssue = !s.issuing && s.restaurantId != null && s.role != null && issued == null,
            code = issued?.code?.let(::grouped),
            countdown = issued?.let { "действует ${RelativeTime.countdown(s.remainingMs)} · одноразовый" },
            expiredText = if (s.expired) "Код истёк. Выпустите новый." else null,
            adbHint = issued?.let { "$ADB_PREFIX ${it.code}" },
            errorText = s.error?.takeIf { it !is ConsoleError.InvalidInput || it.field != IssueCode.FIELD_NAME }?.let(::errorText),
            nameError = (s.error as? ConsoleError.InvalidInput)?.takeIf { it.field == IssueCode.FIELD_NAME }
                ?.let { "Имя длиннее ${IssueCode.NAME_MAX} символов" },
        )
    }

    private fun roleTitle(r: DeviceRole): String = when (r) {
        DeviceRole.Kitchen -> "Кухня"
        DeviceRole.Hall -> "Зал"
        DeviceRole.Assembly -> "Сборка"
    }

    /** Читаемая группировка по 4 символа; копируется исходный код без пробелов. */
    private fun grouped(code: String): String = code.chunked(GROUP).joinToString(" ")

    private fun errorText(e: ConsoleError): String = when (e) {
        ConsoleError.Network -> "Нет связи с сервером"
        ConsoleError.Forbidden -> "Недостаточно прав"
        is ConsoleError.RateLimited -> "Слишком много запросов, повторите позже"
        is ConsoleError.Validation -> "Сервер отклонил запрос: ${e.message}"
        is ConsoleError.InvalidInput -> "Выберите ресторан и роль"
        else -> "Не удалось выпустить код"
    }

    private companion object {
        const val GROUP = 4
        const val ADB_PREFIX =
            "adb shell am broadcast -a ru.restofleet.agent.DEV_PROVISION -n ru.restofleet.agent/.DevConfigReceiver --es code"
    }
}
