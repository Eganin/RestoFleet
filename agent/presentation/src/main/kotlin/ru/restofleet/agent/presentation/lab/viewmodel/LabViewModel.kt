package ru.restofleet.agent.presentation.lab.viewmodel

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.error.MqttNotConfigured
import ru.restofleet.agent.domain.usecase.ExportCapabilityReport
import ru.restofleet.agent.domain.usecase.GetDeviceInfo
import ru.restofleet.agent.domain.usecase.PublishCheckResult
import ru.restofleet.agent.domain.usecase.RunCapabilityChecks
import ru.restofleet.agent.presentation.lab.viewmodel.LabIntent.User
import ru.restofleet.agent.presentation.lab.viewmodel.mapper.LabUiStateMapper
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabSession
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/**
 * Состояние меняется только на главном потоке (scope — `Main.immediate`); тяжёлые use case идут через [io].
 * «Запустить всё» гоняет проверки по одной, чтобы строки получали статусы по мере готовности.
 */
class LabViewModel(
    private val runChecks: RunCapabilityChecks,
    private val exportReport: ExportCapabilityReport,
    private val publishResult: PublishCheckResult,
    private val getDeviceInfo: GetDeviceInfo,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : BaseMviViewModel<LabSession, LabUiState, LabIntent, LabAction, LabEffect>(
    store = MviStore(LabSession(), LabReducer(), LabUiStateMapper()::map),
    scope = scope,
) {
    init {
        scope.launch { reduce(LabAction.DeviceLoaded(withContext(io) { getDeviceInfo() })) }
    }

    override fun onIntent(intent: LabIntent) {
        when (intent) {
            User.RunAll -> runAll()
            is User.RunOne -> runOne(intent.id)
            is User.SelectFilter -> reduce(LabAction.FilterSelected(intent.filter))
            User.Export -> export()
            is User.SendToMqtt -> sendToMqtt(intent.id)
            is User.OpenSettings -> emitEffect(LabEffect.OpenSystemSettings(intent.target))
        }
    }

    private fun runAll() {
        if (state.isRunningAll) return
        reduce(LabAction.RunStarted(CheckId.entries.toSet(), isAll = true))
        scope.launch {
            for (id in CheckId.entries) {
                reduce(LabAction.ResultsArrived(runOnIo(id)))
            }
        }
    }

    private fun runOne(id: CheckId) {
        if (state.isRunningAll || id in state.running) return
        reduce(LabAction.RunStarted(setOf(id)))
        scope.launch { reduce(LabAction.ResultsArrived(runOnIo(id))) }
    }

    private suspend fun runOnIo(id: CheckId) = withContext(io) { runChecks(setOf(id)) }

    // ExportCapabilityReport заново прогоняет все проверки (≈10 с и больше), поэтому есть признак isExporting.
    private fun export() {
        if (state.isExporting) return
        reduce(LabAction.ExportingChanged(true))
        scope.launch {
            val outcome = withContext(io) { exportReport() }
            reduce(LabAction.ExportingChanged(false))
            emitEffect(
                LabEffect.ShowMessage(
                    when (outcome) {
                        is Outcome.Success -> LabMessage.Exported(outcome.value)
                        is Outcome.Failure -> LabMessage.Error(outcome.error.code)
                    },
                ),
            )
        }
    }

    private fun sendToMqtt(id: CheckId) {
        val result = state.results[id]
        if (result == null) {
            emitEffect(LabEffect.ShowMessage(LabMessage.NoResult))
            return
        }
        scope.launch {
            val outcome = withContext(io) { publishResult(result) }
            emitEffect(LabEffect.ShowMessage(publishMessage(id, outcome)))
        }
    }

    private fun publishMessage(id: CheckId, outcome: Outcome<Unit, DomainError>): LabMessage = when (outcome) {
        is Outcome.Success -> LabMessage.Published(id)
        is Outcome.Failure -> when (val error = outcome.error) {
            MqttNotConfigured -> LabMessage.MqttNotConfigured
            else -> LabMessage.Error(error.code)
        }
    }
}
