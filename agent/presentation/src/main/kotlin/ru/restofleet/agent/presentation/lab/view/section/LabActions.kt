package ru.restofleet.agent.presentation.lab.view.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.lab.viewmodel.LabIntent

/** Нижняя панель: «Запустить всё» и «Экспорт» (во время экспорта — с индикатором и недоступна). */
@Composable
internal fun LabActions(
    isRunningAll: Boolean,
    isExporting: Boolean,
    onIntent: (LabIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { onIntent(LabIntent.User.RunAll) }, enabled = !isRunningAll) {
            Text(stringResource(R.string.lab_run_all))
        }
        OutlinedButton(onClick = { onIntent(LabIntent.User.Export) }, enabled = !isExporting) {
            if (isExporting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.lab_exporting), Modifier.padding(start = 8.dp))
            } else {
                Text(stringResource(R.string.lab_export))
            }
        }
    }
}
