package ru.restofleet.agent.presentation.lab.view.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.restofleet.agent.presentation.lab.viewmodel.LabIntent
import ru.restofleet.agent.presentation.lab.viewmodel.TabUi

@Composable
internal fun FilterTabs(tabs: List<TabUi>, onIntent: (LabIntent) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { tab ->
            FilterChip(
                selected = tab.selected,
                onClick = { onIntent(LabIntent.User.SelectFilter(tab.filter)) },
                label = { Text(tab.label) },
            )
        }
    }
}
