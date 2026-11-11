package ru.restofleet.agent.presentation.setup.view.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.BadgeMark
import ru.restofleet.agent.presentation.common.PermissionRow
import ru.restofleet.agent.presentation.common.SettingsTarget
import ru.restofleet.agent.presentation.common.text

/** Строки разрешений с кнопкой «Открыть» системные настройки (мастер и экран статуса). */
@Composable
fun PermissionsSection(rows: List<PermissionRow>, onOpen: (SettingsTarget) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BadgeMark(row.badge, isRunning = false)
                Column(Modifier.weight(1f)) {
                    Text(row.label.text(), style = MaterialTheme.typography.bodyLarge)
                    Text(row.state.text(), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { onOpen(row.target) }) { Text(stringResource(R.string.perm_open_settings)) }
            }
        }
    }
}
