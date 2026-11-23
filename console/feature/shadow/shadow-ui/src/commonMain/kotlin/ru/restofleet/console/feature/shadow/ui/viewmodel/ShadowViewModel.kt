package ru.restofleet.console.feature.shadow.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.console.core.mvi.defaultViewModelScope
import ru.restofleet.console.feature.shadow.domain.AwaitShadowApplied
import ru.restofleet.console.feature.shadow.domain.LoadShadow
import ru.restofleet.console.feature.shadow.domain.RenderEditableJson
import ru.restofleet.console.feature.shadow.domain.SaveResult
import ru.restofleet.console.feature.shadow.domain.SaveShadowForm
import ru.restofleet.console.feature.shadow.domain.SaveShadowJson
import ru.restofleet.console.feature.shadow.domain.ShadowForm
import ru.restofleet.console.feature.shadow.ui.viewmodel.mapper.ShadowUiStateMapper
import ru.restofleet.shared.kernel.Outcome

class ShadowViewModel(
    private val deviceId: String,
    private val loadShadow: LoadShadow,
    private val saveForm: SaveShadowForm,
    private val saveJson: SaveShadowJson,
    private val renderJson: RenderEditableJson,
    private val awaitApplied: AwaitShadowApplied,
    scope: CoroutineScope = defaultViewModelScope(),
) : BaseMviViewModel<ShadowSession, ShadowUiState, ShadowIntent, ShadowAction, ShadowEffect>(
    store = MviStore(ShadowSession(), ShadowReducer(), ShadowUiStateMapper()::map),
    scope = scope,
) {
    private var watch: Job? = null

    init {
        load()
    }

    override fun onIntent(intent: ShadowIntent) {
        when (intent) {
            ShadowIntent.User.Refresh -> load()
            ShadowIntent.User.StartEdit -> state.state?.let { reduce(ShadowAction.FormOpened(ShadowForm.from(it.desired))) }
            ShadowIntent.User.StartJson -> state.state?.let { reduce(ShadowAction.JsonOpened(renderJson(it.desired))) }
            ShadowIntent.User.Cancel -> reduce(ShadowAction.EditCancelled)
            is ShadowIntent.User.FieldChanged -> reduce(ShadowAction.FieldChanged(intent.field, intent.value))
            is ShadowIntent.User.AppAdded -> reduce(ShadowAction.AppAdded(intent.name))
            is ShadowIntent.User.AppRemoved -> reduce(ShadowAction.AppRemoved(intent.name))
            is ShadowIntent.User.JsonChanged -> reduce(ShadowAction.JsonChanged(intent.text))
            ShadowIntent.User.Save -> save()
        }
    }

    private fun load() {
        reduce(ShadowAction.LoadStarted)
        scope.launch {
            when (val result = loadShadow(deviceId)) {
                is Outcome.Success -> reduce(ShadowAction.Loaded(result.value))
                is Outcome.Failure -> reduce(ShadowAction.LoadFailed(result.error))
            }
        }
    }

    /** V-4: экран сам переходит в «Применено», когда агент применил desired. */
    private fun followUntilApplied() {
        watch?.cancel()
        watch = scope.launch { awaitApplied(deviceId).collect { reduce(ShadowAction.Loaded(it)) } }
    }

    private fun save() {
        val s = state
        val shadow = s.state ?: return
        if (s.saving) return
        reduce(ShadowAction.SaveStarted)
        scope.launch {
            val result = when (s.mode) {
                ShadowMode.Form -> s.form?.let { saveForm(deviceId, shadow.desired, it) } ?: SaveResult.NoChanges
                ShadowMode.Json -> saveJson(deviceId, s.jsonText)
                ShadowMode.View -> SaveResult.NoChanges
            }
            when (result) {
                is SaveResult.Saved -> {
                    reduce(ShadowAction.Saved(result.state))
                    if (result.state.pending) followUntilApplied()
                }
                SaveResult.NoChanges -> reduce(ShadowAction.NothingToSave)
                is SaveResult.Invalid -> reduce(ShadowAction.Rejected(result.errors))
                is SaveResult.Failed -> reduce(ShadowAction.SaveFailed(result.error))
            }
        }
    }
}
