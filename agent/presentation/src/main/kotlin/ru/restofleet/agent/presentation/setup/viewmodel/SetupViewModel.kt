package ru.restofleet.agent.presentation.setup.viewmodel

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.restofleet.agent.domain.error.ProvisionError
import ru.restofleet.agent.domain.usecase.GetPermissions
import ru.restofleet.agent.domain.usecase.Provision
import ru.restofleet.agent.presentation.common.guarded
import ru.restofleet.agent.presentation.setup.api.ISetupRouter
import ru.restofleet.agent.presentation.setup.viewmodel.SetupIntent.User
import ru.restofleet.agent.presentation.setup.viewmodel.mapper.SetupUiStateMapper
import ru.restofleet.agent.presentation.setup.viewmodel.model.SetupSession
import ru.restofleet.agent.presentation.setup.viewmodel.model.SetupStep
import ru.restofleet.agent.presentation.setup.viewmodel.model.canSubmit
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore

/**
 * Мастер настройки (FR-AG-04 базово, FR-AG-10): разрешения (переиспользуют `AndroidWizardPermissions` через
 * [GetPermissions]) → адрес стенда и код → [Provision] → итог (устройство, роль, ресторан). Use cases идут на [io],
 * состояние меняется на главном потоке. Повторная отправка во время регистрации игнорируется: код одноразовый.
 * Навигация — через [router].
 */
class SetupViewModel(
    private val getPermissions: GetPermissions,
    private val provision: Provision,
    private val router: ISetupRouter,
    labAvailable: Boolean,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope = defaultScope(),
) : BaseMviViewModel<SetupSession, SetupUiState, SetupIntent, SetupAction, SetupEffect>(
    store = MviStore(SetupSession(labAvailable = labAvailable), SetupReducer(), SetupUiStateMapper()::map),
    scope = scope,
) {
    init {
        refreshPermissions()
    }

    override fun onIntent(intent: SetupIntent) {
        when (intent) {
            User.RefreshPermissions -> refreshPermissions()
            is User.OpenSettings -> router.openSettings(intent.target)
            User.Next -> if (state.step == SetupStep.PERMISSIONS) {
                reduce(SetupAction.StepChanged(SetupStep.REGISTRATION))
            }
            User.Back -> if (state.step == SetupStep.REGISTRATION) {
                reduce(SetupAction.StepChanged(SetupStep.PERMISSIONS))
            }
            is User.EditBaseUrl -> reduce(SetupAction.BaseUrlChanged(intent.text))
            is User.EditCode -> reduce(SetupAction.CodeChanged(intent.text))
            User.Submit -> submit()
            User.Finish -> if (state.step == SetupStep.DONE) router.openStatus()
            User.OpenLab -> if (state.labAvailable) router.openLab()
        }
    }

    private fun refreshPermissions() {
        scope.launch {
            guarded({}) { withContext(io) { getPermissions() } }?.let { reduce(SetupAction.PermissionsLoaded(it)) }
        }
    }

    private fun submit() {
        if (!state.canSubmit) return
        val baseUrl = state.baseUrl
        val code = state.code
        reduce(SetupAction.SubmitStarted)
        scope.launch {
            var failure: ProvisionError = ProvisionError.Failed(UNKNOWN)
            val outcome = guarded({ failure = ProvisionError.Failed(it::class.simpleName ?: UNKNOWN) }) {
                withContext(io) { provision(baseUrl, code) }
            }
            reduce(
                when (outcome) {
                    is Outcome.Success -> SetupAction.Provisioned(outcome.value)
                    is Outcome.Failure -> SetupAction.SubmitFailed(outcome.error)
                    null -> SetupAction.SubmitFailed(failure)
                },
            )
        }
    }

    private companion object {
        const val UNKNOWN = "Unknown"

        fun defaultScope() = CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, e -> Log.e("SetupViewModel", "unhandled", e) },
        )
    }
}
