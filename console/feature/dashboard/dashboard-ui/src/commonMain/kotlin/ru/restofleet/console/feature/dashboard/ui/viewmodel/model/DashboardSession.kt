package ru.restofleet.console.feature.dashboard.ui.viewmodel.model

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.contract.MviState
import ru.restofleet.console.feature.dashboard.domain.Dashboard

data class DashboardSession(
    val loading: Boolean = true,
    val data: Dashboard? = null,
    val error: ConsoleError? = null,
) : MviState
