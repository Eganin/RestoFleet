package ru.restofleet.agent.presentation.status

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.restofleet.agent.presentation.status.viewmodel.StatusIntent.External
import ru.restofleet.agent.presentation.status.viewmodel.StatusViewModel

/**
 * Склейка ViewModel и экрана статуса: пока экран на переднем плане, ViewModel обновляет снимок сама; при уходе с
 * экрана (пауза, переход в Lab) обновление останавливается.
 */
@Composable
fun StatusContainer(viewModel: StatusViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onIntent(External.Shown)
        onPauseOrDispose { viewModel.onIntent(External.Hidden) }
    }
    StatusScreen(uiState, viewModel::onIntent)
}
