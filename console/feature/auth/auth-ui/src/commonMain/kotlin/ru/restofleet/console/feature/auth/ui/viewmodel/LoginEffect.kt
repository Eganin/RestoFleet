package ru.restofleet.console.feature.auth.ui.viewmodel

import ru.restofleet.console.core.mvi.contract.MviEffect

sealed interface LoginEffect : MviEffect {
    /** Убрать клавиатуру при отправке формы. */
    data object HideKeyboard : LoginEffect
}
