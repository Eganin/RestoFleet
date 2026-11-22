package ru.restofleet.console.feature.incidents.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import ru.restofleet.console.core.ui.RuleBadge
import ru.restofleet.console.feature.incidents.ui.viewmodel.IncidentRowUi
import ru.restofleet.console.feature.incidents.ui.viewmodel.IncidentsIntent
import ru.restofleet.console.feature.incidents.ui.viewmodel.IncidentsUiState
import ru.restofleet.console.feature.incidents.ui.viewmodel.IncidentsViewModel

@Composable
fun IncidentsContainer(viewModel: IncidentsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    IncidentsScreen(state, viewModel::onIntent, modifier)
}

/** Инциденты (FR-CN-10): открытые и закрытые за сутки; один инцидент заменяет россыпь одинаковых тревог. */
@Composable
fun IncidentsScreen(state: IncidentsUiState, onIntent: (IncidentsIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = RfTheme.colors
    when {
        state.loading -> Box(modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        state.errorText != null && state.open.isEmpty() && state.resolved.isEmpty() ->
            ErrorState(state.errorText, { onIntent(IncidentsIntent.User.Refresh) }, modifier)
        state.emptyText != null -> EmptyState(state.emptyText, modifier)
        else -> LazyColumn(modifier.fillMaxSize()) {
            item { Header("Открытые · ${state.open.size}") }
            items(state.open, key = { it.id }) { IncidentRow(it, onIntent) }
            item { Header("Закрытые за сутки · ${state.resolved.size}") }
            items(state.resolved, key = { it.id }) { IncidentRow(it, onIntent) }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text, color = RfTheme.colors.fg, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = RfSpacing.L, vertical = RfSpacing.S),
    )
}

@Composable
private fun IncidentRow(r: IncidentRowUi, onIntent: (IncidentsIntent) -> Unit) {
    val colors = RfTheme.colors
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().clickable { onIntent(IncidentsIntent.User.Open(r.id)) }
            .padding(horizontal = RfSpacing.L, vertical = RfSpacing.S),
        Arrangement.SpaceBetween,
        Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.title, color = colors.fg, fontWeight = FontWeight.Medium)
            Text(r.subtitle, color = colors.muted, fontSize = 13.sp)
        }
        RuleBadge(r.ruleId, r.tone)
    }
}
