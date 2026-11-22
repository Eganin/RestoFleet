package ru.restofleet.agent

import androidx.compose.runtime.Composable
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import ru.restofleet.agent.presentation.common.SettingsTarget
import ru.restofleet.agent.presentation.lab.LabContainer
import ru.restofleet.agent.presentation.lab.viewmodel.LabViewModel

/** Только debug: Capability Lab и вкладка Device Owner; ViewModel живут в `InstanceKeeper` и переживают поворот. */
class DebugLabScreen(
    private val createLab: () -> LabViewModel,
    private val deviceOwner: DeviceOwnerTab,
) : LabScreen {
    override fun content(keeper: InstanceKeeper, openSettings: (SettingsTarget) -> Unit): @Composable () -> Unit {
        val lab = keeper.getOrCreate(LabViewModel::class) { createLab() }
        val owner = deviceOwner.content(keeper)
        return { AgentTabHost(lab = { LabContainer(lab, onOpenSettings = openSettings) }, deviceOwner = owner) }
    }
}
