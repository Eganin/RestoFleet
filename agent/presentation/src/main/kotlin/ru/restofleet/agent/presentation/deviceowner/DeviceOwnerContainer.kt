package ru.restofleet.agent.presentation.deviceowner

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.ObserveEffects
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerEffect
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerIntent.User
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerViewModel
import ru.restofleet.agent.presentation.deviceowner.viewmodel.mapper.toStringSpec

/**
 * Склейка ViewModel и экрана: состояние — через `collectAsStateWithLifecycle`, эффекты — через [ObserveEffects].
 * Диалог перезагрузки живёт здесь; если экран закрыт, не дождавшись ответа (смена вкладки, поворот), это отказ:
 * без ответа проверка D2 осталась бы висеть, а перезагрузка без явного «да» не выполняется никогда.
 */
@Composable
fun DeviceOwnerContainer(viewModel: DeviceOwnerViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    var confirmReboot by remember { mutableStateOf(false) }

    // Вкладку открыли: роль могли назначить командой dpm, пока экран был закрыт
    LaunchedEffect(viewModel) { viewModel.onIntent(User.Refresh) }
    DisposableEffect(viewModel) {
        onDispose { if (confirmReboot) viewModel.onIntent(User.ConfirmReboot(false)) }
    }

    ObserveEffects(viewModel.effect) { effect ->
        when (effect) {
            is DeviceOwnerEffect.ShowMessage -> {
                val spec = effect.text.toStringSpec()
                val text = resources.getString(spec.res, *spec.args.toTypedArray())
                scope.launch { snackbarHostState.showSnackbar(text) }
            }
            DeviceOwnerEffect.ConfirmReboot -> confirmReboot = true
        }
    }

    if (confirmReboot) {
        val answer = { confirmed: Boolean ->
            confirmReboot = false
            viewModel.onIntent(User.ConfirmReboot(confirmed))
        }
        AlertDialog(
            onDismissRequest = { answer(false) },
            title = { Text(stringResource(R.string.do_confirm_title)) },
            text = { Text(stringResource(R.string.do_confirm_text)) },
            confirmButton = {
                TextButton(onClick = { answer(true) }) { Text(stringResource(R.string.do_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { answer(false) }) { Text(stringResource(R.string.do_confirm_no)) }
            },
        )
    }

    DeviceOwnerScreen(uiState, viewModel::onIntent, snackbarHostState = snackbarHostState)
}
