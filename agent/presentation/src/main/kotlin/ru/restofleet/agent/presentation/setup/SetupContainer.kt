package ru.restofleet.agent.presentation.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.restofleet.agent.presentation.setup.viewmodel.SetupIntent.User
import ru.restofleet.agent.presentation.setup.viewmodel.SetupViewModel

/** Склейка ViewModel и экрана мастера; после возврата из системных настроек разрешения перечитываются. */
@Composable
fun SetupContainer(viewModel: SetupViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onIntent(User.RefreshPermissions) }
    SetupScreen(uiState, viewModel::onIntent)
}
