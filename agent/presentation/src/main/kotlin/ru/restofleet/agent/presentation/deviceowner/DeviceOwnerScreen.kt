package ru.restofleet.agent.presentation.deviceowner

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.deviceowner.view.section.ApkSection
import ru.restofleet.agent.presentation.deviceowner.view.section.DeviceOwnerHeader
import ru.restofleet.agent.presentation.deviceowner.view.section.DeviceOwnerRow
import ru.restofleet.agent.presentation.deviceowner.view.section.TargetSection
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerIntent
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerUiState
import ru.restofleet.agent.presentation.deviceowner.viewmodel.mapper.DeviceOwnerUiStateMapper
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.DeviceOwnerSession

/** Экран Device Owner (макет `#mvp`, второй экран). Без ViewModel — только состояние и интенты. */
@Composable
fun DeviceOwnerScreen(
    uiState: DeviceOwnerUiState,
    onIntent: (DeviceOwnerIntent) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Scaffold не отступает от навигационной панели для bottomBar — делаем это сами
            Button(
                onClick = { onIntent(DeviceOwnerIntent.User.RunAll) },
                enabled = !uiState.isRunningAll,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.do_run_all)) }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item(key = "header") { DeviceOwnerHeader(uiState) }
            items(uiState.rows, key = { it.id }) { row ->
                DeviceOwnerRow(row, onIntent)
                HorizontalDivider()
            }
            item(key = "apk") { ApkSection(uiState, onIntent) }
            item(key = "target") { TargetSection(uiState, onIntent) }
            item(key = "footer") {
                Text(
                    stringResource(R.string.do_footer),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val previewOwner = DeviceInfo(36, "Pixel Tablet", true, AgentMode.DEVICE_OWNER, "0.1.0-mvp")

private fun previewSession(device: DeviceInfo = previewOwner) = DeviceOwnerSession(
    device = device,
    results = listOf(
        CheckResult(CheckId.D1, CheckStatus.OK, mapOf("mode" to "DEVICE_OWNER"), null, 0L),
        CheckResult(CheckId.D3, CheckStatus.OK, emptyMap(), "succeeded", 0L),
        CheckResult(CheckId.D6, CheckStatus.LIMITED, emptyMap(), "не выдано: PACKAGE_USAGE_STATS", 0L),
        CheckResult(CheckId.R13, CheckStatus.OK, mapOf("serial" to "****4QZ0"), null, 0L),
    ).associateBy { it.id },
)

@Preview(showBackground = true, widthDp = 411, heightDp = 900)
@Composable
private fun DeviceOwnerScreenActivePreview() {
    MaterialTheme { DeviceOwnerScreen(DeviceOwnerUiStateMapper().map(previewSession()), onIntent = {}) }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 900)
@Composable
private fun DeviceOwnerScreenNormalModePreview() {
    val normal = previewOwner.copy(mode = AgentMode.NORMAL)
    MaterialTheme { DeviceOwnerScreen(DeviceOwnerUiStateMapper().map(previewSession(normal)), onIntent = {}) }
}
