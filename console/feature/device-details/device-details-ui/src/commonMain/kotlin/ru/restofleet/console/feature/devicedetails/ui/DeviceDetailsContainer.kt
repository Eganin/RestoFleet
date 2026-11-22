package ru.restofleet.console.feature.devicedetails.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.DetailsViewModel

@Composable
fun DeviceDetailsContainer(
    viewModel: DetailsViewModel,
    shadowContent: @Composable () -> Unit,
    commandsContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    DeviceDetailsScreen(state, viewModel::onIntent, shadowContent, commandsContent, modifier)
}
