package ru.restofleet.console.feature.restaurants.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import ru.restofleet.console.feature.restaurants.ui.viewmodel.RestaurantsIntent
import ru.restofleet.console.feature.restaurants.ui.viewmodel.RestaurantsUiState
import ru.restofleet.console.feature.restaurants.ui.viewmodel.RestaurantsViewModel

@Composable
fun RestaurantsContainer(viewModel: RestaurantsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    RestaurantsScreen(state, viewModel::onIntent, modifier)
}

/** Список ресторанов с поиском (FR-CN-03); нажатие открывает устройства ресторана. */
@Composable
fun RestaurantsScreen(state: RestaurantsUiState, onIntent: (RestaurantsIntent) -> Unit, modifier: Modifier = Modifier) {
    val colors = RfTheme.colors
    Column(modifier.fillMaxSize().padding(horizontal = RfSpacing.L)) {
        Text("Рестораны", color = colors.fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = state.query,
            onValueChange = { onIntent(RestaurantsIntent.User.SearchChanged(it)) },
            label = { Text("Название, код, город") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.errorText != null && state.rows.isEmpty() -> ErrorState(state.errorText, { onIntent(RestaurantsIntent.User.Refresh) })
            state.emptyText != null -> EmptyState(state.emptyText)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.rows, key = { it.id }) { row ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onIntent(RestaurantsIntent.User.Open(row.id)) }
                            .padding(vertical = RfSpacing.S),
                    ) {
                        Text(row.title, color = colors.fg, fontWeight = FontWeight.Medium)
                        Text(row.subtitle, color = colors.muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
