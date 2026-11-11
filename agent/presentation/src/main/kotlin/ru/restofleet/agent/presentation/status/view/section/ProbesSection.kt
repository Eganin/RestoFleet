package ru.restofleet.agent.presentation.status.view.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.BadgeMark
import ru.restofleet.agent.presentation.common.text
import ru.restofleet.agent.presentation.status.viewmodel.ProbeRow

/** Последние результаты проверок доступности: цель, `up`/`down`, HTTP-код и время или ошибка. */
@Composable
fun ProbesSection(rows: List<ProbeRow>) {
    if (rows.isEmpty()) {
        Text(stringResource(R.string.status_no_probes), style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BadgeMark(row.badge, isRunning = false)
                Column(Modifier.weight(1f)) {
                    Text("${row.id} · ${row.state.text()}", style = MaterialTheme.typography.bodyLarge)
                    Text(row.detail.text(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
