package ru.restofleet.console.feature.alerts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.restofleet.console.core.designsystem.RfSpacing
import ru.restofleet.console.core.designsystem.RfTheme
import ru.restofleet.console.core.ui.EmptyState
import ru.restofleet.console.core.ui.ErrorState
import ru.restofleet.console.core.ui.RfTabs
import ru.restofleet.console.core.ui.RuleBadge
import ru.restofleet.console.core.ui.SwipeActionRow
import ru.restofleet.console.feature.alerts.ui.viewmodel.AlertsIntent
import ru.restofleet.console.feature.alerts.ui.viewmodel.AlertsTab
import ru.restofleet.console.feature.alerts.ui.viewmodel.AlertsUiState
import ru.restofleet.console.feature.alerts.ui.viewmodel.AlertsViewModel

@Composable
fun AlertsContainer(viewModel: AlertsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    AlertsScreen(state, viewModel::onIntent, modifier)
}

/**
 * Тревоги (FR-CN-08): вкладки «Открытые · Подтв. · Закрытые», свайп вправо — подтвердить, влево — закрыть
 * (диалог с обязательным комментарием). У автоматически закрытых метка «авто».
 */
@Composable
fun AlertsScreen(state: AlertsUiState, onIntent: (AlertsIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = RfTheme.colors
    Column(modifier.fillMaxSize()) {
        RfTabs(state.tabs, state.selectedTab, { onIntent(AlertsIntent.User.SelectTab(AlertsTab.entries[it])) })
        state.actionErrorText?.let { Text(it, color = colors.crit, modifier = Modifier.padding(horizontal = RfSpacing.L)) }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.errorText != null && state.rows.isEmpty() ->
                ErrorState(state.errorText, { onIntent(AlertsIntent.User.Refresh) })
            state.emptyText != null -> EmptyState(state.emptyText)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.rows, key = { it.id }) { r ->
                    SwipeActionRow(
                        rightLabel = "Подтвердить",
                        leftLabel = "Закрыть",
                        onSwipeRight = { if (r.canAcknowledge) onIntent(AlertsIntent.User.Acknowledge(r.id)) },
                        onSwipeLeft = { if (r.canResolve) onIntent(AlertsIntent.User.RequestResolve(r.id)) },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().background(colors.bg).clickable { onIntent(AlertsIntent.User.OpenDevice(r.deviceId)) }
                                .padding(RfSpacing.L),
                            Arrangement.SpaceBetween,
                            Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.title, color = colors.fg, fontWeight = FontWeight.Medium)
                                Text(r.line, color = colors.muted, fontSize = 13.sp)
                            }
                            if (r.auto) Text("авто", color = colors.muted, fontSize = 12.sp, modifier = Modifier.padding(end = RfSpacing.S))
                            RuleBadge(r.ruleId, r.tone)
                        }
                    }
                }
            }
        }
    }
    state.dialogTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { onIntent(AlertsIntent.User.DismissDialog) },
            title = { Text("Закрыть: $title") },
            text = {
                OutlinedTextField(
                    value = state.comment,
                    onValueChange = { onIntent(AlertsIntent.User.CommentChanged(it)) },
                    label = { Text("Комментарий (обязательно)") },
                    isError = state.commentError != null,
                    supportingText = state.commentError?.let { { Text(it) } },
                )
            },
            confirmButton = {
                TextButton(onClick = { onIntent(AlertsIntent.User.ConfirmResolve) }, enabled = !state.acting) { Text("Закрыть") }
            },
            dismissButton = { TextButton(onClick = { onIntent(AlertsIntent.User.DismissDialog) }) { Text("Отмена") } },
        )
    }
}
