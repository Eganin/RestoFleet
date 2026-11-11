package ru.restofleet.agent.presentation.root

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.restofleet.agent.presentation.root.viewmodel.RootIntent.User
import ru.restofleet.agent.presentation.root.viewmodel.RootViewModel
import ru.restofleet.agent.presentation.root.viewmodel.model.AgentScreen

/**
 * Выбор экрана по состоянию навигации; сами экраны собирает composition root (`agent:app`). [lab] — `null` в
 * release: экрана Lab нет (реш. 140). «Назад» на Lab возвращает на экран, откуда его открыли.
 */
@Composable
fun RootContainer(
    viewModel: RootViewModel,
    setup: @Composable () -> Unit,
    status: @Composable () -> Unit,
    lab: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = uiState.handlesBack) { viewModel.onIntent(User.Back) }
    Box(modifier) {
        when (uiState.screen) {
            AgentScreen.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            AgentScreen.SETUP -> setup()
            AgentScreen.STATUS -> status()
            AgentScreen.LAB -> lab?.invoke() ?: status()
        }
    }
}
