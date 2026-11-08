package ru.restofleet.agent.presentation.lab.view.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.lab.viewmodel.CommandsUi
import ru.restofleet.agent.presentation.lab.viewmodel.LabIntent
import ru.restofleet.agent.presentation.lab.viewmodel.mapper.labelRes

/** Секция «Команды»: поля цели (C2, C3) и кнопки C1–C4; результат команды показывает строка C1–C4 списка. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommandsSection(commands: CommandsUi, onIntent: (LabIntent) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.lab_commands_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = commands.pkg,
            onValueChange = { onIntent(LabIntent.User.EditCommandPackage(it)) },
            label = { Text(stringResource(R.string.lab_commands_package)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = commands.url,
            onValueChange = { onIntent(LabIntent.User.EditCommandUrl(it)) },
            label = { Text(stringResource(R.string.lab_commands_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            commands.buttons.forEach { button ->
                OutlinedButton(
                    onClick = { onIntent(LabIntent.User.RunCommand(button.command)) },
                    enabled = !button.isRunning,
                ) {
                    if (button.isRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    }
                    Text(stringResource(button.command.labelRes()))
                }
            }
        }
    }
}
