package ru.restofleet.console.feature.shadow.ui.viewmodel

import ru.restofleet.console.core.mvi.Reducer
import ru.restofleet.console.feature.shadow.domain.ShadowFields
import ru.restofleet.console.feature.shadow.domain.ShadowForm

class ShadowReducer : Reducer<ShadowAction, ShadowSession> {
    override fun reduce(action: ShadowAction, state: ShadowSession): ShadowSession = when (action) {
        ShadowAction.LoadStarted -> state.copy(loading = state.state == null, error = null)
        is ShadowAction.Loaded -> state.copy(state = action.state, loading = false, error = null)
        is ShadowAction.LoadFailed -> state.copy(loading = false, error = action.error)
        is ShadowAction.FormOpened -> state.copy(
            mode = ShadowMode.Form, form = action.form, fieldErrors = emptyMap(), saveError = null, notice = null,
        )
        is ShadowAction.JsonOpened -> state.copy(
            mode = ShadowMode.Json, jsonText = action.text, fieldErrors = emptyMap(), saveError = null, notice = null,
        )
        ShadowAction.EditCancelled -> state.copy(mode = ShadowMode.View, form = null, fieldErrors = emptyMap(), saveError = null)
        is ShadowAction.FieldChanged ->
            state.copy(form = state.form?.let { edit(it, action) }, fieldErrors = state.fieldErrors - action.field)
        is ShadowAction.AppAdded -> state.copy(
            form = state.form?.let { f -> f.copy(watchedApps = apps(f.watchedApps + action.name.trim())) },
            fieldErrors = state.fieldErrors - ShadowFields.APPS,
        )
        is ShadowAction.AppRemoved -> state.copy(
            form = state.form?.let { it.copy(watchedApps = it.watchedApps - action.name) },
            fieldErrors = state.fieldErrors - ShadowFields.APPS,
        )
        is ShadowAction.JsonChanged -> state.copy(jsonText = action.text, saveError = null)
        ShadowAction.SaveStarted -> state.copy(saving = true, saveError = null, fieldErrors = emptyMap(), notice = null)
        is ShadowAction.Saved -> state.copy(
            state = action.state, saving = false, mode = ShadowMode.View, form = null, fieldErrors = emptyMap(),
            notice = if (action.state.published == false) Notice.SavedNotPublished else Notice.Saved,
        )
        ShadowAction.NothingToSave -> state.copy(saving = false, notice = Notice.NoChanges)
        is ShadowAction.Rejected -> state.copy(saving = false, fieldErrors = action.errors)
        is ShadowAction.SaveFailed -> state.copy(saving = false, saveError = action.error)
    }

    private fun apps(list: List<String>): List<String> = list.filter { it.isNotEmpty() }.distinct()

    private fun edit(form: ShadowForm, a: ShadowAction.FieldChanged): ShadowForm = when (a.field) {
        ShadowFields.INTERVAL -> form.copy(intervalSec = a.value)
        ShadowFields.BATTERY -> form.copy(batteryLowPct = a.value)
        ShadowFields.TEMP -> form.copy(tempHighC = a.value)
        ShadowFields.FG_ABSENT -> form.copy(fgAbsentMin = a.value)
        else -> form
    }
}
