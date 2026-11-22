package ru.restofleet.console.feature.alerts.ui.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviEffect
import ru.restofleet.console.core.mvi.contract.MviIntent
import ru.restofleet.console.core.mvi.contract.MviState
import ru.restofleet.console.feature.alerts.domain.AlertItem
import ru.restofleet.console.feature.alerts.domain.AlertLists

enum class AlertsTab(val title: String) {
    Open("Открытые"),
    Acknowledged("Подтв."),
    Resolved("Закрытые"),
}

data class AlertsSession(
    val tab: AlertsTab = AlertsTab.Open,
    val lists: AlertLists? = null,
    val loading: Boolean = true,
    val error: ConsoleError? = null,
    /** Тревога, закрытие которой ждёт комментария (диалог). */
    val resolving: AlertItem? = null,
    val comment: String = "",
    val commentError: String? = null,
    val acting: Boolean = false,
    val actionError: ConsoleError? = null,
) : MviState

sealed interface AlertsIntent : MviIntent {
    sealed interface User : AlertsIntent {
        data class SelectTab(val tab: AlertsTab) : User
        data class Acknowledge(val id: String) : User
        data class RequestResolve(val id: String) : User
        data class CommentChanged(val text: String) : User
        data object ConfirmResolve : User
        data object DismissDialog : User
        data object Refresh : User
        data class OpenDevice(val deviceId: String) : User
    }
}

sealed interface AlertsAction : MviAction {
    data object LoadStarted : AlertsAction
    data class Loaded(val lists: AlertLists) : AlertsAction
    data class LoadFailed(val error: ConsoleError) : AlertsAction
    data class TabSelected(val tab: AlertsTab) : AlertsAction
    data class Pushed(val alert: AlertItem) : AlertsAction
    data class ResolveRequested(val alert: AlertItem) : AlertsAction
    data class CommentChanged(val text: String) : AlertsAction
    data class CommentRejected(val reason: String) : AlertsAction
    data object DialogDismissed : AlertsAction
    data object ActionStarted : AlertsAction
    data class ActionSucceeded(val alert: AlertItem) : AlertsAction
    data class ActionFailed(val error: ConsoleError) : AlertsAction
}

sealed interface AlertsEffect : MviEffect {
    data class ShowMessage(val text: String) : AlertsEffect
}

@Immutable
data class AlertRowUi(
    val id: String,
    val deviceId: String,
    val title: String,
    val line: String,
    val ruleId: String,
    val tone: StatusTone,
    val auto: Boolean,
    val canAcknowledge: Boolean,
    val canResolve: Boolean,
)

@Immutable
data class AlertsUiState(
    val tabs: List<String>,
    val selectedTab: Int,
    val rows: List<AlertRowUi>,
    val loading: Boolean,
    val errorText: String?,
    val emptyText: String?,
    val dialogTitle: String?,
    val comment: String,
    val commentError: String?,
    val acting: Boolean,
    val actionErrorText: String?,
)
