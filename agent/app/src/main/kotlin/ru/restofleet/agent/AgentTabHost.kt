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
 * [deviceOwner] = `null` в release (решение 140): остаётся один Lab без полосы вкладок.
 */
@Composable
fun AgentTabHost(
    lab: @Composable () -> Unit,
    deviceOwner: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableIntStateOf(AgentTab.LAB.ordinal) }
    // Без вкладки Device Owner (release, решение 140) переключать нечего: полосы вкладок нет, показывается Lab
    val tabs = AgentTab.entries.filter { it != AgentTab.DEVICE_OWNER || deviceOwner != null }
    val current = AgentTab.entries[selected].takeIf { it in tabs } ?: AgentTab.LAB
    Column(modifier) {
        if (tabs.size > 1) {
            PrimaryTabRow(selectedTabIndex = tabs.indexOf(current)) {
                tabs.forEach { tab ->
                    Tab(
                        selected = current == tab,
                        onClick = { selected = tab.ordinal },
                        text = { Text(stringResource(tab.label)) },
                    )
                }
            }
        }
        when (current) {
            AgentTab.LAB -> lab()
            AgentTab.DEVICE_OWNER -> deviceOwner?.invoke()
        }
    }
}
