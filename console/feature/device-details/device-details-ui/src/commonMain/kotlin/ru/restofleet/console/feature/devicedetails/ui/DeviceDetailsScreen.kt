package ru.restofleet.console.feature.devicedetails.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.restofleet.console.core.designsystem.RfSpacing
import ru.restofleet.console.core.designsystem.RfTheme
import ru.restofleet.console.core.designsystem.StatusTone
import ru.restofleet.console.core.ui.ErrorState
import ru.restofleet.console.core.ui.LineChart
import ru.restofleet.console.core.ui.MetricTile
import ru.restofleet.console.core.ui.PhaseWaterfall
import ru.restofleet.console.core.ui.RfTabs
import ru.restofleet.console.core.ui.RuleBadge
import ru.restofleet.console.core.ui.StatusChip
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.DetailsIntent
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.DetailsUiState
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.model.DetailsTab

/**
 * Карточка устройства (FR-CN-05): шапка, вкладки «Состояние · Проверки · События · Тень», шторка «Команда».
 * Вкладку «Тень» и шторку наполняют родитель ([shadowContent], [commandsContent]): фича о них ничего не знает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailsScreen(
    state: DetailsUiState,
    onIntent: (DetailsIntent) -> Unit,
    shadowContent: @Composable () -> Unit,
    commandsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RfTheme.colors
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = RfSpacing.S), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onIntent(DetailsIntent.User.Back) }) { Text("‹ Назад") }
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        if (state.title.isEmpty()) {
            ErrorState(state.errorText ?: "Нет данных", { onIntent(DetailsIntent.User.Refresh) })
            return@Column
        }
        Column(Modifier.padding(horizontal = RfSpacing.L), verticalArrangement = Arrangement.spacedBy(RfSpacing.XS)) {
            Text(state.title, color = colors.fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(RfSpacing.S), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(state.statusText, state.statusTone)
                state.rules.forEach { RuleBadge(it, state.statusTone) }
            }
            Text(state.subtitle, color = colors.muted, fontSize = 13.sp)
        }
        RfTabs(state.tabs, state.selectedTab, { onIntent(DetailsIntent.User.SelectTab(DetailsTab.entries[it])) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (DetailsTab.entries[state.selectedTab]) {
                DetailsTab.State -> StateTab(state, onIntent)
                DetailsTab.Probes -> ProbesTab(state)
                DetailsTab.Events -> EventsTab(state, onIntent)
                DetailsTab.Shadow -> shadowContent()
            }
        }
    }
    if (state.commandSheet) {
        ModalBottomSheet(onDismissRequest = { onIntent(DetailsIntent.User.CloseCommands) }) { commandsContent() }
    }
}

@Composable
private fun StateTab(state: DetailsUiState, onIntent: (DetailsIntent) -> Unit) {
    val colors = RfTheme.colors
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = RfSpacing.L),
        verticalArrangement = Arrangement.spacedBy(RfSpacing.S),
    ) {
        items(state.tiles.chunked(2)) { pair ->
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(RfSpacing.S)) {
                pair.forEach { MetricTile(it.label, it.value, Modifier.weight(1f), it.caption, it.tone, it.alert) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(RfSpacing.XS)) {
                Text(state.chartCaption, color = colors.muted, fontSize = 12.sp)
                LineChart(state.chart, threshold = state.chartThreshold, tone = StatusTone.Warn)
            }
        }
        item { Text(state.footer, color = colors.muted, fontSize = 12.sp) }
        item {
            Button(
                onClick = { onIntent(DetailsIntent.User.OpenCommands) },
                enabled = state.commandsEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Команда") }
        }
    }
}

@Composable
private fun ProbesTab(state: DetailsUiState) {
    val colors = RfTheme.colors
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = RfSpacing.L), verticalArrangement = Arrangement.spacedBy(RfSpacing.S)) {
        state.probesErrorText?.let { item { Text(it, color = colors.crit) } }
        if (!state.probesLoaded) item { CircularProgressIndicator() }
        items(state.probesLast, key = { it.target }) { p ->
            Column(verticalArrangement = Arrangement.spacedBy(RfSpacing.XS)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(p.target, color = colors.fg, fontWeight = FontWeight.Medium)
                    Text(p.summary, color = if (p.ok) colors.ok else colors.crit, fontSize = 13.sp)
                }
                PhaseWaterfall(p.phases)
            }
        }
        items(state.probeRows) { r ->
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Text("${r.target} · ${r.text}", color = if (r.ok) colors.fg else colors.crit, fontSize = 13.sp)
                Text(r.ago, color = colors.muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun EventsTab(state: DetailsUiState, onIntent: (DetailsIntent) -> Unit) {
    val colors = RfTheme.colors
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = RfSpacing.L), verticalArrangement = Arrangement.spacedBy(RfSpacing.S)) {
        state.eventsErrorText?.let { item { Text(it, color = colors.crit) } }
        if (!state.eventsLoaded) item { CircularProgressIndicator() }
        items(state.eventRows) { e ->
            Column {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text(e.title, color = colors.fg, fontWeight = FontWeight.Medium)
                    Text(e.ago, color = colors.muted, fontSize = 12.sp)
                }
                Text(e.text, color = colors.muted, fontSize = 13.sp)
            }
        }
        if (state.canLoadMoreEvents) {
            item {
                LaunchedEffect(Unit) { onIntent(DetailsIntent.User.LoadMoreEvents) }
                CircularProgressIndicator()
            }
        }
    }
}
