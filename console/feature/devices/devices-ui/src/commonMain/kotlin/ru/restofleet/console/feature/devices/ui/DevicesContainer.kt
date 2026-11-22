package ru.restofleet.console.feature.devices.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import ru.restofleet.console.feature.devices.ui.viewmodel.DevicesViewModel

@Composable
fun DevicesContainer(viewModel: DevicesViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    DevicesScreen(state, viewModel::onIntent, modifier)
}
