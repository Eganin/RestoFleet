package ru.restofleet.agent.presentation.lab.view.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.lab.viewmodel.LabIntent

/**
 * Секция «Отладка» (только debug): проверка ACL брокера (M2) и серия событий для проверки досылки outbox (M7).
 * Тексты кнопок — контракт для 3.7: их ищут через `uiautomator dump`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DebugSection(onIntent: (LabIntent) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.lab_debug_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onIntent(LabIntent.User.PublishForeignTopic) }) {
                Text(stringResource(R.string.lab_debug_foreign_topic))
            }
            OutlinedButton(onClick = { onIntent(LabIntent.User.GenerateDebugEvents) }) {
                Text(stringResource(R.string.lab_debug_generate_events))
            }
        }
    }
}
