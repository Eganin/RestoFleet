package ru.restofleet.agent.presentation.status.api

import ru.restofleet.agent.presentation.common.SettingsTarget

/** Навигация экрана статуса наружу: системные настройки (нехватка разрешения) и Lab (только debug). */
interface IStatusRouter {
    fun openSettings(target: SettingsTarget)
    fun openLab()

    /** Учётки нет (R-13: например, Keystore её не вернул и запись стёрта) — в мастер регистрации. */
    fun openSetup()
}
