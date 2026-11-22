package ru.restofleet.agent.presentation.status.api

import ru.restofleet.agent.presentation.common.SettingsTarget

/** Навигация экрана статуса наружу: системные настройки (нехватка разрешения) и Lab (только debug). */
interface IStatusRouter {
    fun openSettings(target: SettingsTarget)
    fun openLab()
}
