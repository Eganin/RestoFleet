package ru.restofleet.agent.presentation.root.viewmodel

import ru.restofleet.console.core.mvi.contract.MviIntent

sealed interface RootIntent : MviIntent {
    sealed interface User : RootIntent {
        /** Открыть Capability Lab (только debug, реш. 140). */
        data object OpenLab : User

        /** Системная кнопка «назад» на экране Lab. */
        data object Back : User
    }

    sealed interface External : RootIntent {
        /** Мастер завершён: учётка сохранена. */
        data object SetupCompleted : External
    }
}
