package ru.restofleet.console.feature.incidents.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
import ru.restofleet.console.core.ui.ErrorState
import ru.restofleet.console.core.ui.RuleBadge
import ru.restofleet.console.core.ui.StatusChip

@Composable
fun IncidentDetailsContainer(viewModel: IncidentDetailsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    IncidentDetailsScreen(state, viewModel::onIntent, modifier)
}

/** Карточка инцидента: тип, рестораны, «Почему так решили», ошибки проверок, подтверждение с комментарием. */
@Composable
fun IncidentDetailsScreen(
    state: IncidentDetailsUiState,
    onIntent: (IncidentDetailsIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RfTheme.colors
    Column(modifier.fillMaxSize()) {
        TextButton(onClick = { onIntent(IncidentDetailsIntent.User.Back) }) { Text("‹ Назад") }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.title.isEmpty() ->
                ErrorState(state.errorText ?: "Нет данных", { onIntent(IncidentDetailsIntent.User.Refresh) })
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = RfSpacing.L),
                verticalArrangement = Arrangement.spacedBy(RfSpacing.S),
            ) {
                item { Text(state.title, color = colors.fg, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(RfSpacing.XS)) {
                        StatusChip(state.statusText, state.statusTone)
                        RuleBadge(state.ruleId, state.statusTone)
                        Text(state.restaurants, color = colors.muted, fontSize = 13.sp)
                    }
                }
                item { Text("Почему так решили", color = colors.fg, fontWeight = FontWeight.SemiBold) }
                state.explanation.forEach { line -> item { Text(line, color = colors.fg, fontSize = 14.sp) } }
                state.errorsLine?.let { item { Text(it, color = colors.muted, fontSize = 13.sp) } }
                state.ackInfo?.let { item { Text(it, color = colors.ok, fontSize = 14.sp) } }
                if (state.canAcknowledge) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(RfSpacing.XS)) {
                            OutlinedTextField(
                                value = state.comment,
                                onValueChange = { onIntent(IncidentDetailsIntent.User.CommentChanged(it)) },
                                label = { Text("Комментарий (необязательно)") },
                                isError = state.commentError != null,
                                supportingText = state.commentError?.let { { Text(it) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            state.ackErrorText?.let { Text(it, color = colors.crit, fontSize = 13.sp) }
                            Button(
                                onClick = { onIntent(IncidentDetailsIntent.User.Acknowledge) },
                                enabled = !state.acknowledging,
                            ) { Text("Подтвердить") }
                        }
                    }
                }
            }
        }
    }
}
