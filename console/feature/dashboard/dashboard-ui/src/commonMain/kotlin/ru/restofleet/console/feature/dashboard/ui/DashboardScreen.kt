package ru.restofleet.console.feature.dashboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.restofleet.console.core.designsystem.RfSpacing
import ru.restofleet.console.core.designsystem.RfTheme
import ru.restofleet.console.core.ui.EmptyState
import ru.restofleet.console.core.ui.ErrorState
import ru.restofleet.console.core.ui.MetricTile
import ru.restofleet.console.core.ui.RuleBadge
import ru.restofleet.console.feature.dashboard.ui.viewmodel.DashboardIntent
import ru.restofleet.console.feature.dashboard.ui.viewmodel.DashboardUiState

/** Обзор (FR-CN-02): счётчики, рестораны с проблемами, последние события. */
@Composable
fun DashboardScreen(state: DashboardUiState, onIntent: (DashboardIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = RfTheme.colors
    when {
        state.loading -> Column(modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        state.counters.isEmpty() -> ErrorState(
            state.errorText ?: "Нет данных",
            onRetry = { onIntent(DashboardIntent.User.Refresh) },
            modifier = modifier,
        )
        state.empty -> EmptyState("Устройств пока нет", modifier, "Зарегистрируйте первое устройство")
        else -> LazyColumn(
            modifier.fillMaxSize().padding(horizontal = RfSpacing.L),
            verticalArrangement = Arrangement.spacedBy(RfSpacing.S),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Обзор", color = colors.fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { onIntent(DashboardIntent.User.Logout) }) { Text("Выйти") }
                }
            }
            state.errorText?.let { item { Text(it, color = colors.crit) } }
            items(state.counters.chunked(2)) { pair ->
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(RfSpacing.S)) {
                    pair.forEach { c ->
                        MetricTile(c.label, c.value, Modifier.weight(1f), c.caption, c.tone, alert = false)
                    }
                }
            }
            item { Section("Рестораны с проблемами") { onIntent(DashboardIntent.User.OpenAlerts) } }
            items(state.problems, key = { it.key }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (p.deviceId != null) {
                            onIntent(DashboardIntent.User.OpenDevice(p.deviceId))
                        } else {
                            onIntent(DashboardIntent.User.OpenRestaurant(p.restaurantId, p.restaurantName))
                        }
                    }.padding(vertical = RfSpacing.S),
                    Arrangement.SpaceBetween,
                    Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.restaurantName, color = colors.fg, fontWeight = FontWeight.Medium)
                        Text(p.line, color = colors.muted, fontSize = 13.sp)
                    }
                    RuleBadge(p.ruleId, p.tone)
                }
            }
            item { Section("Последние события", null) }
            items(state.recent, key = { it.key }) { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { onIntent(DashboardIntent.User.OpenDevice(r.deviceId)) }
                        .padding(vertical = RfSpacing.S),
                    Arrangement.SpaceBetween,
                    Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.where, color = colors.fg, fontWeight = FontWeight.Medium)
                        Text("${r.title} · ${r.ago}", color = colors.muted, fontSize = 13.sp)
                    }
                    RuleBadge(r.ruleId, r.tone)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, onAll: (() -> Unit)?) {
    val colors = RfTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = RfSpacing.M), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(title, color = colors.fg, fontWeight = FontWeight.SemiBold)
        if (onAll != null) TextButton(onClick = onAll) { Text("все ›") }
    }
}
