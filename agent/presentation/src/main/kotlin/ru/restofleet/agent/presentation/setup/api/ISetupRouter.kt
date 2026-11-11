package ru.restofleet.agent.presentation.setup.api

import ru.restofleet.agent.presentation.common.SettingsTarget

/** Навигация мастера наружу: системные настройки, экран статуса после регистрации, Lab (только debug). */
interface ISetupRouter {
    fun openSettings(target: SettingsTarget)
    fun openStatus()
    fun openLab()
}
