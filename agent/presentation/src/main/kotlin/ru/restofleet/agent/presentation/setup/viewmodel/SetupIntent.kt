package ru.restofleet.agent.presentation.setup.viewmodel

import ru.restofleet.agent.presentation.common.SettingsTarget
import ru.restofleet.console.core.mvi.contract.MviIntent

sealed interface SetupIntent : MviIntent {
    sealed interface User : SetupIntent {
        /** Экран снова виден (вернулись из настроек): перечитать разрешения. */
        data object RefreshPermissions : User
        data class OpenSettings(val target: SettingsTarget) : User

        /** Разрешения → регистрация; разрешения не обязательны (их можно выдать позже). */
        data object Next : User

        /** Регистрация → разрешения. */
        data object Back : User
        data class EditBaseUrl(val text: String) : User
        data class EditCode(val text: String) : User
        data object Submit : User

        /** Результат → экран статуса. */
        data object Finish : User
        data object OpenLab : User
    }
}
