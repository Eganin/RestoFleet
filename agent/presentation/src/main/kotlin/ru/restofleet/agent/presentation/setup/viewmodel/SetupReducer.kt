package ru.restofleet.agent.presentation.setup.viewmodel

import ru.restofleet.agent.presentation.setup.viewmodel.model.SetupSession
import ru.restofleet.agent.presentation.setup.viewmodel.model.SetupStep
import ru.restofleet.console.core.mvi.Reducer

/**
 * Переходы мастера. Шаг меняется только вперёд-назад между разрешениями и регистрацией; на результат ведёт лишь
 * [SetupAction.Provisioned], и с результата назад не вернуться. Правка адреса или кода снимает прошлую ошибку.
 */
class SetupReducer : Reducer<SetupAction, SetupSession> {
    override fun reduce(action: SetupAction, state: SetupSession): SetupSession = when (action) {
        is SetupAction.PermissionsLoaded -> state.copy(granted = action.granted)
        is SetupAction.StepChanged ->
            if (state.step == SetupStep.DONE || action.step == SetupStep.DONE || state.isSubmitting) {
                state
            } else {
                state.copy(step = action.step)
            }
        is SetupAction.BaseUrlChanged -> state.copy(baseUrl = action.text, error = null)
        is SetupAction.CodeChanged -> state.copy(code = action.text, error = null)
        SetupAction.SubmitStarted -> state.copy(isSubmitting = true, error = null)
        is SetupAction.SubmitFailed -> state.copy(isSubmitting = false, error = action.error)
        is SetupAction.Provisioned -> state.copy(
            step = SetupStep.DONE,
            isSubmitting = false,
            error = null,
            code = "",
            registration = action.registration,
        )
    }
}
