package ru.restofleet.console.feature.devices.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.restofleet.console.core.designsystem.RfSpacing
import ru.restofleet.console.core.designsystem.RfTheme
import ru.restofleet.console.core.ui.EmptyState
import ru.restofleet.console.core.ui.ErrorState
import ru.restofleet.console.core.ui.RuleBadge
import ru.restofleet.console.core.ui.StatusChip
import ru.restofleet.console.feature.devices.ui.viewmodel.DevicesIntent
import ru.restofleet.console.feature.devices.ui.viewmodel.DevicesUiState
import ru.restofleet.console.feature.devices.ui.viewmodel.FilterOptionUi

/** Список устройств (FR-CN-04): поиск, фильтры «Проблемы · Ресторан · Роль · Версия · Статус», бесконечный список. */
@Composable
fun DevicesScreen(state: DevicesUiState, onIntent: (DevicesIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = RfTheme.colors
    Column(modifier.fillMaxSize().padding(horizontal = RfSpacing.L)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(state.title, color = colors.fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onIntent(DevicesIntent.User.OpenProvisioning) }) { Text("+ Устройство") }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = { onIntent(DevicesIntent.User.SearchChanged(it)) },
            label = { Text("Имя, ресторан, модель") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(RfSpacing.S)) {
            FilterChip(
                selected = state.problemsOnly,
                onClick = { onIntent(DevicesIntent.User.ToggleProblems) },
                label = { Text("Проблемы") },
            )
            MenuChip("Ресторан", state.restaurants) { onIntent(DevicesIntent.User.RestaurantSelected(it)) }
            MenuChip("Роль", state.roles) { onIntent(DevicesIntent.User.RoleSelected(it)) }
            MenuChip("Версия", state.versions) { onIntent(DevicesIntent.User.VersionSelected(it)) }
            MenuChip("Статус", state.statuses) { onIntent(DevicesIntent.User.StatusSelected(it)) }
        }
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.errorText != null && state.rows.isEmpty() ->
                ErrorState(state.errorText, { onIntent(DevicesIntent.User.Refresh) })
            state.emptyText != null -> EmptyState(state.emptyText)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.rows, key = { it.id }) { row ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onIntent(DevicesIntent.User.OpenDevice(row.id)) }
                            .padding(vertical = RfSpacing.S),
                        Arrangement.SpaceBetween,
                        Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.title, color = colors.fg, fontWeight = FontWeight.Medium)
                            Text(row.metrics, color = colors.muted, fontSize = 13.sp)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(RfSpacing.XS)) {
                            StatusChip(row.statusText, row.tone)
                            row.ruleId?.let { RuleBadge(it, row.ruleTone) }
                        }
                    }
                }
                if (state.canLoadMore) item { LoadMoreTrigger { onIntent(DevicesIntent.User.LoadMore) } }
            }
        }
    }
}

@Composable
private fun LoadMoreTrigger(onVisible: () -> Unit) {
    LaunchedEffect(Unit) { onVisible() }
    Box(Modifier.fillMaxWidth().padding(RfSpacing.M), Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun MenuChip(label: String, options: List<FilterOptionUi>, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.selected && it.value != null }
    Box {
        FilterChip(
            selected = current != null,
            onClick = { open = true },
            label = { Text(current?.label ?: "$label ▾") },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o.label) }, onClick = {
                    open = false
                    onSelect(o.value)
                })
            }
        }
    }
}
