package ru.restofleet.agent.presentation.root.viewmodel

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.restofleet.agent.domain.model.RegistrationState
import ru.restofleet.agent.domain.usecase.GetRegistration
import ru.restofleet.agent.presentation.common.guarded
import ru.restofleet.agent.presentation.root.viewmodel.RootIntent.External
import ru.restofleet.agent.presentation.root.viewmodel.RootIntent.User
import ru.restofleet.agent.presentation.root.viewmodel.model.AgentScreen
import ru.restofleet.agent.presentation.root.viewmodel.model.RootSession
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore

/**
 * Навигация агента (план 3.6): при создании читает регистрацию на [io] и выбирает мастер или статус; дальше —
 * переходы по намерениям. Сбой чтения учётки — экран статуса (там видна причина), а не мастер.
 */
class RootViewModel(
    private val getRegistration: GetRegistration,
    labAvailable: Boolean,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope = defaultScope(),
) : BaseMviViewModel<RootSession, RootUiState, RootIntent, RootAction, RootEffect>(
    store = MviStore(RootSession(labAvailable = labAvailable), RootReducer(), ::project),
    scope = scope,
) {
    init {
        scope.launch {
            val registration = guarded({}) { withContext(io) { getRegistration() } } ?: RegistrationState.Unavailable
            reduce(RootAction.RegistrationResolved(registration))
        }
    }

    override fun onIntent(intent: RootIntent) {
        when (intent) {
            User.OpenLab -> reduce(RootAction.LabOpened)
            User.Back -> reduce(RootAction.LabClosed)
            External.SetupCompleted -> reduce(RootAction.SetupCompleted)
        }
    }

    private companion object {
        fun project(session: RootSession) = RootUiState(
            screen = session.screen,
            labAvailable = session.labAvailable,
            handlesBack = session.screen == AgentScreen.LAB,
        )

        fun defaultScope() = CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, e -> Log.e("RootViewModel", "unhandled", e) },
        )
    }
}
