package ru.restofleet.agent.presentation.lab.view.section

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.BadgeMark
import ru.restofleet.agent.presentation.lab.viewmodel.CheckRowUi
import ru.restofleet.agent.presentation.lab.viewmodel.LabIntent
import ru.restofleet.agent.presentation.lab.viewmodel.mapper.labelRes

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CheckRow(row: CheckRowUi, onIntent: (LabIntent) -> Unit, modifier: Modifier = Modifier) {
    val id = CheckId.valueOf(row.id)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !row.isRunning) { onIntent(LabIntent.User.RunOne(id)) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BadgeMark(row.badge, row.isRunning)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (row.summary.isNotEmpty()) {
                Text(
                    row.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = SUMMARY_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.settingsTargets.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.settingsTargets.forEach { target ->
                        OutlinedButton(onClick = { onIntent(LabIntent.User.OpenSettings(target)) }) {
                            Text(stringResource(target.labelRes()))
                        }
                    }
                }
            }
        }
        if (row.canSend) {
            TextButton(onClick = { onIntent(LabIntent.User.SendToMqtt(id)) }) {
                Text(stringResource(R.string.lab_to_mqtt))
            }
        }
    }
}

private const val SUMMARY_MAX_LINES = 3
