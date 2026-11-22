package ru.restofleet.console.feature.shadow.ui.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviEffect
import ru.restofleet.console.core.mvi.contract.MviIntent
import ru.restofleet.console.core.mvi.contract.MviState
import ru.restofleet.console.feature.shadow.domain.ShadowForm
import ru.restofleet.console.feature.shadow.domain.ShadowState

enum class ShadowMode { View, Form, Json }

enum class Notice { Saved, SavedNotPublished, NoChanges }

data class ShadowSession(
    val state: ShadowState? = null,
    val loading: Boolean = true,
    val error: ConsoleError? = null,
    val mode: ShadowMode = ShadowMode.View,
    val form: ShadowForm? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val jsonText: String = "",
    val saving: Boolean = false,
    val saveError: ConsoleError? = null,
    val notice: Notice? = null,
) : MviState

sealed interface ShadowIntent : MviIntent {
    sealed interface User : ShadowIntent {
        data object Refresh : User
        data object StartEdit : User
        data object StartJson : User
        data object Cancel : User
        data class FieldChanged(val field: String, val value: String) : User
        data class AppAdded(val name: String) : User
        data class AppRemoved(val name: String) : User
        data class JsonChanged(val text: String) : User
        data object Save : User
    }
}

sealed interface ShadowAction : MviAction {
    data object LoadStarted : ShadowAction
    data class Loaded(val state: ShadowState) : ShadowAction
    data class LoadFailed(val error: ConsoleError) : ShadowAction
    data class FormOpened(val form: ShadowForm) : ShadowAction
    data class JsonOpened(val text: String) : ShadowAction
    data object EditCancelled : ShadowAction
    data class FieldChanged(val field: String, val value: String) : ShadowAction
    data class AppAdded(val name: String) : ShadowAction
    data class AppRemoved(val name: String) : ShadowAction
    data class JsonChanged(val text: String) : ShadowAction
    data object SaveStarted : ShadowAction
    data class Saved(val state: ShadowState) : ShadowAction
    data object NothingToSave : ShadowAction
    data class Rejected(val errors: Map<String, String>) : ShadowAction
    data class SaveFailed(val error: ConsoleError) : ShadowAction
}

sealed interface ShadowEffect : MviEffect {
    data class ShowMessage(val text: String) : ShadowEffect
}

@Immutable
data class ShadowRowUi(val path: String, val text: String, val changed: Boolean)

@Immutable
data class FormFieldUi(val field: String, val label: String, val value: String, val error: String?)

@Immutable
data class ShadowUiState(
    val loading: Boolean,
    val errorText: String?,
    val header: String,
    val banner: String,
    val bannerPending: Boolean,
    val deviceErrors: String?,
    val rows: List<ShadowRowUi>,
    val mode: ShadowMode,
    val fields: List<FormFieldUi>,
    val apps: List<String>,
    val appsError: String?,
    val jsonText: String,
    val jsonError: String?,
    val saving: Boolean,
    val saveErrorText: String?,
    val noticeText: String?,
    val canEdit: Boolean,
)
