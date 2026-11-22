package ru.restofleet.console.feature.auth.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import ru.restofleet.console.core.ui.ObserveEffects
import ru.restofleet.console.feature.auth.ui.viewmodel.LoginEffect
import ru.restofleet.console.feature.auth.ui.viewmodel.LoginViewModel

/** Связывает ViewModel с экраном: состояние, намерения, единственный подписчик эффектов. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LoginContainer(viewModel: LoginViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    ObserveEffects(viewModel.effect) {
        when (it) {
            LoginEffect.HideKeyboard -> keyboard?.hide()
        }
    }
    LoginScreen(state, viewModel::onIntent, modifier)
}
