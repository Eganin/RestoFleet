package ru.restofleet.agent.presentation.deviceowner.view.section

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
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerIntent
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerRowUi

/** Строка проверки с кнопкой «Запустить» (у D5 ещё и «Через 30 с», чтобы успеть нажать Home). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceOwnerRow(
    row: DeviceOwnerRowUi,
    onIntent: (DeviceOwnerIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val id = CheckId.valueOf(row.id)
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BadgeMark(row.badge, row.isRunning)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                row.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = SUMMARY_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onIntent(DeviceOwnerIntent.User.RunOne(id)) },
                    enabled = row.enabled,
                ) { Text(stringResource(R.string.do_run)) }
                if (row.hasDelayedVariant) {
                    OutlinedButton(
                        onClick = { onIntent(DeviceOwnerIntent.User.RunLaunchFromBackground) },
                        enabled = row.enabled,
                    ) { Text(stringResource(R.string.do_run_delayed)) }
                }
            }
        }
    }
}

private const val SUMMARY_MAX_LINES = 3
