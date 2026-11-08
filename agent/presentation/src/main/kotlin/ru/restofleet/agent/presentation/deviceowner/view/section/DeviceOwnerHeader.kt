package ru.restofleet.agent.presentation.deviceowner.view.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerUiState

/** Карточка режима: заголовок («Device Owner активен» / «Обычный режим»), владелец и маскированный серийный номер. */
@Composable
internal fun DeviceOwnerHeader(uiState: DeviceOwnerUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(uiState.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        LabeledValue(stringResource(R.string.do_owner), uiState.owner)
        LabeledValue(stringResource(R.string.do_serial), uiState.serial)
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
