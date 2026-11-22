package ru.restofleet.agent.presentation.root.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.agent.presentation.root.viewmodel.model.AgentScreen

/** [handlesBack] — «назад» перехватывается (Lab → туда, откуда открыли), иначе закрывается Activity. */
@Immutable
data class RootUiState(
    val screen: AgentScreen,
    val labAvailable: Boolean,
    val handlesBack: Boolean,
)
