package ru.restofleet.console.feature.dashboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import ru.restofleet.console.feature.dashboard.ui.viewmodel.DashboardViewModel

@Composable
fun DashboardContainer(viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    DashboardScreen(state, viewModel::onIntent, modifier)
}
