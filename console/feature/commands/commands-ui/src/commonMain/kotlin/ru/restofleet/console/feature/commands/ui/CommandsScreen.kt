package ru.restofleet.console.feature.commands.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.restofleet.console.core.designsystem.RfSpacing
import ru.restofleet.console.core.designsystem.RfTheme
import ru.restofleet.console.core.ui.StatusChip
import ru.restofleet.console.feature.commands.ui.viewmodel.CommandsIntent
import ru.restofleet.console.feature.commands.ui.viewmodel.CommandsUiState
import ru.restofleet.console.feature.commands.ui.viewmodel.CommandsViewModel

@Composable
fun CommandsContainer(viewModel: CommandsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    CommandsScreen(state, viewModel::onIntent, modifier)
}

/** Шторка «Команда устройству» (FR-CN-07): список команд, форма параметров и история. */
@Composable
fun CommandsScreen(state: CommandsUiState, onIntent: (CommandsIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = RfTheme.colors
    Column(modifier.fillMaxWidth().padding(RfSpacing.L), verticalArrangement = Arrangement.spacedBy(RfSpacing.S)) {
        Text("Команда устройству", color = colors.fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (state.loading) CircularProgressIndicator()
        state.errorText?.let {
            Text(it, color = colors.crit)
            TextButton(onClick = { onIntent(CommandsIntent.User.Refresh) }) { Text("Повторить") }
        }
        if (state.formTitle != null) {
            Text(state.formTitle, color = colors.fg, fontWeight = FontWeight.SemiBold)
            state.fields.forEach { f ->
                OutlinedTextField(
                    value = f.value,
                    onValueChange = { onIntent(CommandsIntent.User.InputChanged(f.key, it)) },
                    label = { Text(f.label) },
                    isError = f.error,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(RfSpacing.S)) {
                Button(onClick = { onIntent(CommandsIntent.User.Submit) }, enabled = !state.sending) { Text("Отправить") }
                TextButton(onClick = { onIntent(CommandsIntent.User.CancelForm) }) { Text("Отмена") }
            }
        } else {
            state.specs.forEach { s ->
                Column(
                    Modifier.fillMaxWidth().clickable(enabled = s.enabled) { onIntent(CommandsIntent.User.Select(s.type)) }
                        .padding(vertical = RfSpacing.XS),
                ) {
                    Text(s.title, color = if (s.enabled) colors.fg else colors.muted, fontWeight = FontWeight.Medium)
                    Text(s.reason ?: s.subtitle, color = colors.muted, fontSize = 12.sp)
                }
            }
        }
        state.sendErrorText?.let { Text(it, color = colors.crit) }
        if (state.history.isNotEmpty()) {
            Text("Недавние", color = colors.fg, fontWeight = FontWeight.SemiBold)
            state.history.forEach { r ->
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, color = colors.fg, fontSize = 13.sp)
                        Text(r.text, color = colors.muted, fontSize = 12.sp)
                    }
                    StatusChip(r.statusText, r.tone)
                }
            }
        }
    }
}
