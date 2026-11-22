package ru.restofleet.agent.presentation.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.BadgeMark
import ru.restofleet.agent.presentation.common.InfoRowView
import ru.restofleet.agent.presentation.common.text
import ru.restofleet.agent.presentation.setup.view.section.PermissionsSection
import ru.restofleet.agent.presentation.status.view.section.ProbesSection
import ru.restofleet.agent.presentation.status.viewmodel.StatusIntent
import ru.restofleet.agent.presentation.status.viewmodel.StatusIntent.User
import ru.restofleet.agent.presentation.status.viewmodel.StatusUiState
import ru.restofleet.agent.presentation.status.viewmodel.mapper.StatusUiStateMapper
import ru.restofleet.agent.presentation.status.viewmodel.model.StatusSession

/** Экран статуса агента (FR-AG-05): подключение, регистрация, режим и буфер, разрешения, пробы. Без ViewModel. */
@Composable
fun StatusScreen(uiState: StatusUiState, onIntent: (StatusIntent) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.status_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
            )
            OutlinedButton(onClick = { onIntent(User.Refresh) }) { Text(stringResource(R.string.status_refresh)) }
        }
        if (uiState.isLoading || uiState.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        Section(R.string.status_section_connection) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BadgeMark(uiState.connectionBadge, isRunning = false)
                Text(uiState.connection.text(), style = MaterialTheme.typography.bodyLarge)
            }
            uiState.connectionDetails.forEach { InfoRowView(it) }
        }
        Section(R.string.status_section_device) {
            uiState.registrationNote?.let { Text(it.text(), style = MaterialTheme.typography.bodyMedium) }
            uiState.registration.forEach { InfoRowView(it) }
            uiState.device.forEach { InfoRowView(it) }
        }
        Section(R.string.status_section_permissions) {
            PermissionsSection(uiState.permissions) { onIntent(User.OpenSettings(it)) }
        }
        Section(R.string.status_section_probes) { ProbesSection(uiState.probes) }
        if (uiState.labAvailable) {
            TextButton(onClick = { onIntent(User.OpenLab) }) { Text(stringResource(R.string.open_lab)) }
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    HorizontalDivider()
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    content()
}

@Preview(showBackground = true)
@Composable
private fun StatusScreenPreview() {
    StatusScreen(StatusUiStateMapper().map(StatusSession(labAvailable = true))) {}
}
