package ru.restofleet.console.feature.dashboard.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.console.core.mvi.defaultViewModelScope
import ru.restofleet.console.feature.dashboard.domain.DashboardEvent
import ru.restofleet.console.feature.dashboard.domain.LoadDashboard
import ru.restofleet.console.feature.dashboard.domain.ObserveDashboardEvents
import ru.restofleet.console.feature.dashboard.ui.api.IDashboardRouter
import ru.restofleet.console.feature.dashboard.ui.viewmodel.mapper.DashboardUiStateMapper
import ru.restofleet.console.feature.dashboard.ui.viewmodel.model.DashboardSession
import ru.restofleet.shared.kernel.Outcome

/**
 * Обзор: первая загрузка, затем перечитывание по сигналам realtime. Сигналы склеиваются в один ожидающий запрос
 * (`CONFLATED`): всплеск событий не порождает всплеск запросов, а последнее событие не теряется.
 */
class DashboardViewModel(
    private val loadDashboard: LoadDashboard,
    observeEvents: ObserveDashboardEvents,
    private val router: IDashboardRouter,
    scope: CoroutineScope = defaultViewModelScope(),
) : BaseMviViewModel<DashboardSession, DashboardUiState, DashboardIntent, DashboardAction, DashboardEffect>(
    store = MviStore(DashboardSession(), DashboardReducer(), DashboardUiStateMapper()::map),
    scope = scope,
) {
    private val reloads = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { for (ignored in reloads) load() }
        scope.launch {
            observeEvents().collect {
                when (it) {
                    is DashboardEvent.SummaryPushed -> reduce(DashboardAction.SummaryPushed(it.summary))
                    DashboardEvent.ReloadNeeded -> reloads.trySend(Unit)
                }
            }
        }
        reloads.trySend(Unit)
    }

    override fun onIntent(intent: DashboardIntent) {
        when (intent) {
            DashboardIntent.User.Refresh -> reloads.trySend(Unit)
            is DashboardIntent.User.OpenDevice -> router.openDevice(intent.deviceId)
            is DashboardIntent.User.OpenRestaurant -> router.openRestaurant(intent.restaurantId, intent.name)
            DashboardIntent.User.OpenAlerts -> router.openAlerts()
            DashboardIntent.User.Logout -> router.logout()
        }
    }

    override fun onDestroy() {
        reloads.close()
        super.onDestroy()
    }

    private suspend fun load() {
        reduce(DashboardAction.LoadStarted)
        when (val result = loadDashboard()) {
            is Outcome.Success -> reduce(DashboardAction.Loaded(result.value))
            is Outcome.Failure -> reduce(DashboardAction.LoadFailed(result.error))
        }
    }
}
