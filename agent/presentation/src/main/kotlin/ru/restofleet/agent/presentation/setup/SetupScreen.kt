package ru.restofleet.agent.presentation.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.InfoRowView
import ru.restofleet.agent.presentation.common.text
import ru.restofleet.agent.presentation.setup.view.section.PermissionsSection
import ru.restofleet.agent.presentation.setup.viewmodel.SetupIntent
import ru.restofleet.agent.presentation.setup.viewmodel.SetupIntent.User
import ru.restofleet.agent.presentation.setup.viewmodel.SetupUiState
import ru.restofleet.agent.presentation.setup.viewmodel.mapper.SetupUiStateMapper
import ru.restofleet.agent.presentation.setup.viewmodel.model.SetupSession
import ru.restofleet.agent.presentation.setup.viewmodel.model.SetupStep

/** Мастер настройки: шаг — разрешения, регистрация по коду или итог. Без ViewModel (канон MVI). */
@Composable
fun SetupScreen(uiState: SetupUiState, onIntent: (SetupIntent) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(uiState.stepTitle.text(), style = MaterialTheme.typography.titleMedium)
        when (uiState.step) {
            SetupStep.PERMISSIONS -> PermissionsStep(uiState, onIntent)
            SetupStep.REGISTRATION -> RegistrationStep(uiState, onIntent)
            SetupStep.DONE -> DoneStep(uiState, onIntent)
        }
        if (uiState.labAvailable) {
            TextButton(onClick = { onIntent(User.OpenLab) }) { Text(stringResource(R.string.open_lab)) }
        }
    }
}

@Composable
private fun PermissionsStep(uiState: SetupUiState, onIntent: (SetupIntent) -> Unit) {
    Text(stringResource(R.string.setup_permissions_hint), style = MaterialTheme.typography.bodyMedium)
    PermissionsSection(uiState.permissions) { onIntent(User.OpenSettings(it)) }
    Button(onClick = { onIntent(User.Next) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_next))
    }
}

@Composable
private fun RegistrationStep(uiState: SetupUiState, onIntent: (SetupIntent) -> Unit) {
    Text(stringResource(R.string.setup_registration_hint), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = uiState.baseUrl,
        onValueChange = { onIntent(User.EditBaseUrl(it)) },
        label = { Text(stringResource(R.string.setup_base_url)) },
        singleLine = true,
        enabled = !uiState.isSubmitting,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = uiState.code,
        onValueChange = { onIntent(User.EditCode(it)) },
        label = { Text(stringResource(R.string.setup_code)) },
        singleLine = true,
        enabled = !uiState.isSubmitting,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    uiState.error?.let {
        Text(it.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { onIntent(User.Back) }, enabled = !uiState.isSubmitting) {
            Text(stringResource(R.string.setup_back))
        }
        Button(onClick = { onIntent(User.Submit) }, enabled = uiState.canSubmit) {
            Text(stringResource(if (uiState.isSubmitting) R.string.setup_submitting else R.string.setup_submit))
        }
    }
}

@Composable
private fun DoneStep(uiState: SetupUiState, onIntent: (SetupIntent) -> Unit) {
    Text(stringResource(R.string.setup_done_title), style = MaterialTheme.typography.titleMedium)
    uiState.result.forEach { InfoRowView(it) }
    Button(onClick = { onIntent(User.Finish) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_finish))
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupScreenPreview() {
    SetupScreen(SetupUiStateMapper().map(SetupSession(step = SetupStep.REGISTRATION, code = "ABCD"))) {}
}
