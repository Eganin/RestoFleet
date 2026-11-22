package ru.restofleet.agent.presentation.root.viewmodel

import ru.restofleet.agent.domain.model.RegistrationState
import ru.restofleet.agent.presentation.root.viewmodel.model.AgentScreen
import ru.restofleet.agent.presentation.root.viewmodel.model.RootSession
import ru.restofleet.console.core.mvi.Reducer

/**
 * Переходы навигации. Учётка недоступна временно (Keystore, реш. 174) — экран статуса, а не мастер: новый код погасил
 * бы рабочую регистрацию. Lab открывается только из мастера и статуса и только при `labAvailable`.
 */
class RootReducer : Reducer<RootAction, RootSession> {
    override fun reduce(action: RootAction, state: RootSession): RootSession = when (action) {
        is RootAction.RegistrationResolved -> if (state.screen == AgentScreen.LOADING) {
            state.copy(screen = startScreen(action.state))
        } else {
            state
        }
        RootAction.LabOpened -> if (state.labAvailable && state.screen in LAB_ENTRIES) {
            state.copy(screen = AgentScreen.LAB, labReturnTo = state.screen)
        } else {
            state
        }
        RootAction.LabClosed -> if (state.screen == AgentScreen.LAB) {
            state.copy(screen = state.labReturnTo ?: AgentScreen.STATUS, labReturnTo = null)
        } else {
            state
        }
        RootAction.SetupCompleted -> if (state.screen == AgentScreen.SETUP) {
            state.copy(screen = AgentScreen.STATUS)
        } else {
            state
        }
    }

    private fun startScreen(registration: RegistrationState): AgentScreen = when (registration) {
        RegistrationState.NotRegistered -> AgentScreen.SETUP
        is RegistrationState.Registered, RegistrationState.Unavailable -> AgentScreen.STATUS
    }

    private companion object {
        val LAB_ENTRIES = setOf(AgentScreen.SETUP, AgentScreen.STATUS)
    }
}
