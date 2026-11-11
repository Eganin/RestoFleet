package ru.restofleet.agent.presentation.status.viewmodel

import ru.restofleet.agent.presentation.common.SettingsTarget
import ru.restofleet.console.core.mvi.contract.MviIntent

sealed interface StatusIntent : MviIntent {
    sealed interface User : StatusIntent {
        data object Refresh : User
        data class OpenSettings(val target: SettingsTarget) : User
        data object OpenLab : User
    }

    sealed interface External : StatusIntent {
        /** Экран на переднем плане: снимок обновляется периодически. */
        data object Shown : External

        /** Экран ушёл с переднего плана или закрыт: периодическое обновление останавливается. */
        data object Hidden : External
    }
}
