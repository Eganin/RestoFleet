package ru.restofleet.agent

import androidx.compose.runtime.Composable
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import com.arkivanov.essenty.instancekeeper.getOrCreate
import ru.restofleet.agent.presentation.deviceowner.DeviceOwnerContainer
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerViewModel

/** Только debug: вкладка Device Owner; ViewModel живёт в `InstanceKeeper` и переживает поворот экрана. */
class DebugDeviceOwnerTab(private val createViewModel: () -> DeviceOwnerViewModel) : DeviceOwnerTab {
    override fun content(keeper: InstanceKeeper): @Composable () -> Unit {
        val viewModel = keeper.getOrCreate(DeviceOwnerViewModel::class) { createViewModel() }
        return { DeviceOwnerContainer(viewModel) }
    }
}
