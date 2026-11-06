package ru.restofleet.agent.presentation.lab

import android.content.res.Resources
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.ObserveEffects
import ru.restofleet.agent.presentation.lab.viewmodel.LabEffect
import ru.restofleet.agent.presentation.lab.viewmodel.LabMessage
import ru.restofleet.agent.presentation.lab.viewmodel.LabViewModel
import ru.restofleet.agent.presentation.lab.viewmodel.model.SettingsTarget

/**
 * Склейка ViewModel и экрана: состояние — через `collectAsStateWithLifecycle`, эффекты — через [ObserveEffects].
 * Открытие системных настроек делает вызывающий ([onOpenSettings]): Intent-ы живут в `agent:app`.
 */
@Composable
fun LabContainer(viewModel: LabViewModel, onOpenSettings: (SettingsTarget) -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources

    ObserveEffects(viewModel.effect) { effect ->
        when (effect) {
            is LabEffect.ShowMessage -> scope.launch { snackbarHostState.showSnackbar(effect.text.text(resources)) }
            is LabEffect.OpenSystemSettings -> onOpenSettings(effect.target)
        }
    }

    LabScreen(uiState, viewModel::onIntent, snackbarHostState = snackbarHostState)
}

private fun LabMessage.text(resources: Resources): String = when (this) {
    is LabMessage.Exported -> resources.getString(R.string.lab_message_exported, path)
    is LabMessage.Published -> resources.getString(R.string.lab_message_published, id.name)
    is LabMessage.Error -> resources.getString(R.string.lab_message_error, details)
    LabMessage.MqttNotConfigured -> resources.getString(R.string.lab_message_mqtt_not_configured)
    LabMessage.NoResult -> resources.getString(R.string.lab_message_no_result)
}
