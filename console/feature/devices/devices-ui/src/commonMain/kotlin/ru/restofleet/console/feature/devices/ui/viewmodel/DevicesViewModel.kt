package ru.restofleet.console.feature.devices.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.console.core.mvi.defaultViewModelScope
import ru.restofleet.console.feature.devices.domain.DeviceFilter
import ru.restofleet.console.feature.devices.domain.DevicesEvent
import ru.restofleet.console.feature.devices.domain.DevicesPage
import ru.restofleet.console.feature.devices.domain.LoadDevicesPage
import ru.restofleet.console.feature.devices.domain.LoadRestaurantOptions
import ru.restofleet.console.feature.devices.domain.ObserveDevicesEvents
import ru.restofleet.console.feature.devices.ui.api.IDevicesRouter
import ru.restofleet.console.feature.devices.ui.viewmodel.mapper.DevicesUiStateMapper
import ru.restofleet.console.feature.devices.ui.viewmodel.model.DevicesSession
import ru.restofleet.shared.kernel.Outcome

/** Список устройств: фильтры, поиск с задержкой, постраничная подгрузка, обновление статусов по realtime. */
class DevicesViewModel(
    private val loadPage: LoadDevicesPage,
    private val loadRestaurants: LoadRestaurantOptions,
    observeEvents: ObserveDevicesEvents,
    private val router: IDevicesRouter,
    initialFilter: DeviceFilter,
    title: String?,
    scope: CoroutineScope = defaultViewModelScope(),
) : BaseMviViewModel<DevicesSession, DevicesUiState, DevicesIntent, DevicesAction, DevicesEffect>(
    store = MviStore(
        DevicesSession(title = title, filter = initialFilter),
        DevicesReducer(),
        DevicesUiStateMapper()::map,
    ),
    scope = scope,
) {
    private val reloads = Channel<Unit>(Channel.CONFLATED)
    private var searchJob: Job? = null

    init {
        scope.launch { for (ignored in reloads) reload() }
        scope.launch {
            observeEvents().collect {
                when (it) {
                    is DevicesEvent.StatusChanged -> reduce(DevicesAction.StatusChanged(it.deviceId, it.status, it.lastSeenAt))
                    DevicesEvent.ReloadNeeded -> reloads.trySend(Unit)
                }
            }
        }
        scope.launch {
            (loadRestaurants() as? Outcome.Success)?.let { reduce(DevicesAction.RestaurantsLoaded(it.value)) }
        }
        reloads.trySend(Unit)
    }

    override fun onIntent(intent: DevicesIntent) {
        when (intent) {
            is DevicesIntent.User.SearchChanged -> search(intent.query)
            DevicesIntent.User.ToggleProblems -> change { it.copy(problemsOnly = !it.problemsOnly) }
            is DevicesIntent.User.RestaurantSelected -> change { it.copy(restaurantId = intent.restaurantId) }
            is DevicesIntent.User.RoleSelected -> change { it.copy(role = intent.role) }
            is DevicesIntent.User.StatusSelected -> change { it.copy(status = intent.status) }
            is DevicesIntent.User.VersionSelected -> change { it.copy(version = intent.version) }
            DevicesIntent.User.LoadMore -> loadMore()
            DevicesIntent.User.Refresh -> reloads.trySend(Unit)
            is DevicesIntent.User.OpenDevice -> router.openDevice(intent.deviceId)
            DevicesIntent.User.OpenProvisioning -> router.openProvisioning()
        }
    }

    override fun onDestroy() {
        reloads.close()
        super.onDestroy()
    }

    private fun change(transform: (DeviceFilter) -> DeviceFilter) {
        searchJob?.cancel()
        reduce(DevicesAction.FilterChanged(transform(state.filter)))
        reloads.trySend(Unit)
    }

    private fun search(query: String) {
        reduce(DevicesAction.FilterChanged(state.filter.copy(query = query)))
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            reloads.trySend(Unit)
        }
    }

    private suspend fun reload() {
        reduce(DevicesAction.ReloadStarted)
        val requested = state.filter
        val result = loadPage(requested, offset = 0)
        // Ответ на устаревший фильтр отбрасывается: за ним уже стоит новый запрос
        if (state.filter == requested) apply(result)
    }

    private fun loadMore() {
        val s = state
        if (!s.hasMore || s.loadingMore || s.loading) return
        reduce(DevicesAction.MoreStarted)
        scope.launch {
            val result = loadPage(s.filter, offset = s.items.size)
            if (state.filter == s.filter) apply(result)
        }
    }

    private fun apply(result: Outcome<DevicesPage, ConsoleError>) = when (result) {
        is Outcome.Success -> reduce(DevicesAction.PageLoaded(result.value))
        is Outcome.Failure -> reduce(DevicesAction.LoadFailed(result.error))
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
