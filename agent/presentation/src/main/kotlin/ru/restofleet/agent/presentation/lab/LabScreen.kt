package ru.restofleet.agent.presentation.lab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.presentation.lab.view.section.CheckRow
import ru.restofleet.agent.presentation.lab.view.section.CommandsSection
import ru.restofleet.agent.presentation.lab.view.section.DebugSection
import ru.restofleet.agent.presentation.lab.view.section.FilterTabs
import ru.restofleet.agent.presentation.lab.view.section.LabActions
import ru.restofleet.agent.presentation.lab.viewmodel.LabIntent
import ru.restofleet.agent.presentation.lab.viewmodel.LabUiState
import ru.restofleet.agent.presentation.lab.viewmodel.mapper.LabUiStateMapper
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabFilter
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabSession

/** Capability Lab: шапка, вкладки, список проверок и панель действий. Без ViewModel — только состояние и интенты. */
@Composable
fun LabScreen(
    uiState: LabUiState,
    onIntent: (LabIntent) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Scaffold не отступает от навигационной панели для bottomBar — делаем это сами
            LabActions(uiState.isRunningAll, uiState.isExporting, onIntent, Modifier.navigationBarsPadding())
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Text(
                uiState.header,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            FilterTabs(uiState.tabs, onIntent)
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                if (uiState.commands != null || uiState.debugTools) {
                    item(key = "commands") {
                        uiState.commands?.let { CommandsSection(it, onIntent) }
                        if (uiState.debugTools) DebugSection(onIntent)
                        HorizontalDivider()
                    }
                }
                items(uiState.rows, key = { it.id }) { row ->
                    CheckRow(row, onIntent)
                    HorizontalDivider()
                }
            }
        }
    }
}

private val previewDevice = DeviceInfo(36, "Pixel 9a", true, AgentMode.NORMAL, "0.1.0-mvp")

private fun previewResult(
    id: CheckId,
    status: CheckStatus,
    raw: Map<String, String> = emptyMap(),
    note: String? = null,
) = CheckResult(id, status, raw, note, 0L)

private fun previewSession(filter: LabFilter = LabFilter.ALL) = LabSession(
    results = listOf(
        previewResult(CheckId.R1, CheckStatus.OK, linkedMapOf("level" to "87%", "plugged" to "AC", "temp" to "38 °C")),
        previewResult(CheckId.R3, CheckStatus.LIMITED, note = "status NONE · headroom NaN (эмулятор)"),
        previewResult(CheckId.R5, CheckStatus.LIMITED, note = "<unknown ssid> · нужна геолокация"),
        previewResult(CheckId.R10, CheckStatus.NOT_APPLICABLE, note = "только в режиме Device Owner"),
        previewResult(CheckId.P3, CheckStatus.UNAVAILABLE, note = "EPERM · нет доступа к локальной сети"),
    ).associateBy { it.id },
    filter = filter,
    device = previewDevice,
)

@Preview(showBackground = true, widthDp = 411, heightDp = 800)
@Composable
private fun LabScreenAllPreview() {
    MaterialTheme { LabScreen(LabUiStateMapper().map(previewSession()), onIntent = {}) }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 800)
@Composable
private fun LabScreenProblemsPreview() {
    MaterialTheme { LabScreen(LabUiStateMapper().map(previewSession(LabFilter.PROBLEMS)), onIntent = {}) }
}

@Preview(showBackground = true, widthDp = 900, heightDp = 600)
@Composable
private fun LabScreenRunningTabletPreview() {
    val running = previewSession().copy(
        running = setOf(CheckId.R2, CheckId.R4),
        isRunningAll = true,
        isExporting = true,
    )
    MaterialTheme { LabScreen(LabUiStateMapper().map(running), onIntent = {}) }
}
