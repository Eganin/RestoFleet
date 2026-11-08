package ru.restofleet.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * Lab и экран Device Owner как две вкладки. Выбранная вкладка хранится в `rememberSaveable` (переживает поворот и
 * пересоздание Activity); неактивная вкладка уходит из композиции, её ViewModel остаётся в `InstanceKeeper`.
 */
@Composable
fun AgentTabHost(lab: @Composable () -> Unit, deviceOwner: @Composable () -> Unit, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(AgentTab.LAB.ordinal) }
    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = selected) {
            AgentTab.entries.forEach { tab ->
                Tab(
                    selected = selected == tab.ordinal,
                    onClick = { selected = tab.ordinal },
                    text = { Text(stringResource(tab.label)) },
                )
            }
        }
        when (AgentTab.entries[selected]) {
            AgentTab.LAB -> lab()
            AgentTab.DEVICE_OWNER -> deviceOwner()
        }
    }
}
