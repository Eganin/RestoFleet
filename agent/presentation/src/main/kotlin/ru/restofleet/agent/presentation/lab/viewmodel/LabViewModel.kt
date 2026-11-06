package ru.restofleet.agent.presentation.lab.viewmodel

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    scope: CoroutineScope = defaultScope(),
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
            var firstError: Exception? = null
            try {
                for (id in CheckId.entries) {
                    val results = guarded({ firstError = firstError ?: it }) { runOnIo(id) }
                    reduce(results?.let(LabAction::ResultsArrived) ?: LabAction.RunFinished(setOf(id)))
                }
            } finally {
                reduce(LabAction.RunFinished(CheckId.entries.toSet()))
            }
            firstError?.let(::emitError)
        }
    }

    private fun runOne(id: CheckId) {
        if (state.isRunningAll || id in state.running) return
        reduce(LabAction.RunStarted(setOf(id)))
        scope.launch {
            try {
                guarded(::emitError) { runOnIo(id) }?.let { reduce(LabAction.ResultsArrived(it)) }
            } finally {
                reduce(LabAction.RunFinished(setOf(id)))
            }
        }
    }

    private suspend fun runOnIo(id: CheckId) = withContext(io) { runChecks(setOf(id)) }

    // ExportCapabilityReport заново прогоняет все проверки (≈10 с и больше), поэтому есть признак isExporting.
    // Экспорт — отдельный прогон: во время «Запустить всё» он игнорируется (иначе две параллельные прогонки,
    // а JSON расходится с тем, что видит техник).
    private fun export() {
        if (state.isExporting || state.isRunningAll) return
        reduce(LabAction.ExportingChanged(true))
        scope.launch {
            try {
                val outcome = guarded(::emitError) { withContext(io) { exportReport() } }
                outcome?.let {
                    emitEffect(
                        LabEffect.ShowMessage(
                            when (it) {
                                is Outcome.Success -> LabMessage.Exported(it.value)
                                is Outcome.Failure -> LabMessage.Error(it.error.code)
                            },
                        ),
                    )
                }
            } finally {
                reduce(LabAction.ExportingChanged(false))
            }
        }
    }

    private fun sendToMqtt(id: CheckId) {
        val result = state.results[id]
        if (result == null) {
            emitEffect(LabEffect.ShowMessage(LabMessage.NoResult))
            return
        }
        scope.launch {
            guarded(::emitError) { withContext(io) { publishResult(result) } }
                ?.let { emitEffect(LabEffect.ShowMessage(publishMessage(id, it))) }
        }
    }

    private fun emitError(e: Exception) {
        emitEffect(LabEffect.ShowMessage(LabMessage.Error(e.message ?: e::class.simpleName.orEmpty())))
    }

    /**
     * Сбой use case не должен ни зависнуть в флагах, ни уронить процесс (он общий с сервисом агента): ошибка уходит
     * в [onError], результат — `null`. CancellationException при активном scope (чужой таймаут, отменённый future) —
     * тоже сбой; отмена самого scope (VM уничтожена) пробрасывается. [Error] не перехватывается.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> guarded(onError: (Exception) -> Unit, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            onError(e)
            null
        } catch (e: Exception) {
            onError(e)
            null
        }

    private fun publishMessage(id: CheckId, outcome: Outcome<Unit, DomainError>): LabMessage = when (outcome) {
        is Outcome.Success -> LabMessage.Published(id)
        is Outcome.Failure -> when (val error = outcome.error) {
            MqttNotConfigured -> LabMessage.MqttNotConfigured
            else -> LabMessage.Error(error.code)
        }
    }
}

/** Последняя линия обороны: необработанное исключение корутины VM — в лог, а не в крах процесса агента. */
internal fun labExceptionHandler(log: (Throwable) -> Unit): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e -> log(e) }

private fun defaultScope() = CoroutineScope(
    SupervisorJob() + Dispatchers.Main.immediate + labExceptionHandler { Log.e("LabViewModel", "unhandled", it) },
)
