package ru.restofleet.agent.presentation.status.viewmodel

import ru.restofleet.agent.domain.model.AgentStatus
import ru.restofleet.console.core.mvi.contract.MviAction

sealed interface StatusAction : MviAction {
    data object RefreshStarted : StatusAction
    data class Loaded(val status: AgentStatus) : StatusAction

    /** Чтение не удалось целиком: прошлый снимок остаётся. */
    data object RefreshFinished : StatusAction
}
