package ru.restofleet.agent.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/** Текст [StringSpec] в composable: выбор ресурса и аргументов уже сделан маппером. */
@Composable
internal fun StringSpec.text(): String = stringResource(res, *args.toTypedArray())

/** Строка «подпись — значение». */
@Composable
internal fun InfoRowView(row: InfoRow) {
    Row(Modifier.fillMaxWidth()) {
        Text(row.label.text(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(row.value.text(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
