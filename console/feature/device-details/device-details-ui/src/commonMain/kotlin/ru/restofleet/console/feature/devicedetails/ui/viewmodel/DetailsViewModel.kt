package ru.restofleet.console.feature.devicedetails.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.console.core.mvi.defaultViewModelScope
import ru.restofleet.console.feature.devicedetails.domain.DeviceLiveEvent
import ru.restofleet.console.feature.devicedetails.domain.LoadDeviceCard
import ru.restofleet.console.feature.devicedetails.domain.LoadEvents
import ru.restofleet.console.feature.devicedetails.domain.LoadProbes
import ru.restofleet.console.feature.devicedetails.domain.LoadTemperatureHistory
import ru.restofleet.console.feature.devicedetails.domain.ObserveDeviceLive
import ru.restofleet.console.feature.devicedetails.ui.api.IDeviceDetailsRouter
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.mapper.DetailsUiStateMapper
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.model.DetailsSession
import ru.restofleet.console.feature.devicedetails.ui.viewmodel.model.DetailsTab
import ru.restofleet.shared.kernel.Outcome

/**
 * Карточка устройства: состояние и график загружаются сразу, «Проверки» и «События» — при первом открытии вкладки.
 * Подписка на телеметрию живёт, пока жив ViewModel (отписка — при уходе с экрана), реконнект перечитывает данные.
 */
class DetailsViewModel(
    private val deviceId: String,
    private val loadCard: LoadDeviceCard,
    private val loadTemperature: LoadTemperatureHistory,
    private val loadProbes: LoadProbes,
    private val loadEvents: LoadEvents,
    observeLive: ObserveDeviceLive,
    private val router: IDeviceDetailsRouter,
    scope: CoroutineScope = defaultViewModelScope(),
) : BaseMviViewModel<DetailsSession, DetailsUiState, DetailsIntent, DetailsAction, DetailsEffect>(
    store = MviStore(DetailsSession(), DetailsReducer(), DetailsUiStateMapper()::map),
    scope = scope,
) {
    private val reloads = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { for (ignored in reloads) reload() }
        scope.launch {
            observeLive(deviceId).collect {
                when (it) {
                    is DeviceLiveEvent.TelemetryPushed -> reduce(DetailsAction.TelemetryPushed(it.telemetry))
                    is DeviceLiveEvent.StatusChanged -> reduce(DetailsAction.StatusChanged(it.status, it.lastSeenAt))
                    DeviceLiveEvent.ReloadNeeded -> reloads.trySend(Unit)
                }
            }
        }
        reloads.trySend(Unit)
    }

    override fun onIntent(intent: DetailsIntent) {
        when (intent) {
            is DetailsIntent.User.SelectTab -> selectTab(intent.tab)
            DetailsIntent.User.Refresh -> reloads.trySend(Unit)
            DetailsIntent.User.LoadMoreEvents -> loadEventsPage(state.events?.size ?: 0)
            DetailsIntent.User.OpenCommands -> reduce(DetailsAction.CommandSheet(true))
            DetailsIntent.User.CloseCommands -> reduce(DetailsAction.CommandSheet(false))
            DetailsIntent.User.Back -> router.back()
        }
    }

    override fun onDestroy() {
        reloads.close()
        super.onDestroy()
    }

    private suspend fun reload() {
        reduce(DetailsAction.CardStarted)
        when (val card = loadCard(deviceId)) {
            is Outcome.Failure -> reduce(DetailsAction.CardFailed(card.error))
            is Outcome.Success -> {
                val temp = (loadTemperature(deviceId) as? Outcome.Success)?.value ?: state.temp
                reduce(DetailsAction.CardLoaded(card.value, temp))
            }
        }
        if (state.probes != null) loadProbesNow()
    }

    private fun selectTab(tab: DetailsTab) {
        reduce(DetailsAction.TabSelected(tab))
        when {
            tab == DetailsTab.Probes && state.probes == null -> scope.launch { loadProbesNow() }
            tab == DetailsTab.Events && state.events == null -> loadEventsPage(0)
        }
    }

    private suspend fun loadProbesNow() {
        when (val result = loadProbes(deviceId)) {
            is Outcome.Success -> reduce(DetailsAction.ProbesLoaded(result.value))
            is Outcome.Failure -> reduce(DetailsAction.ProbesFailed(result.error))
        }
    }

    private fun loadEventsPage(offset: Int) {
        if (state.eventsLoadingMore || (offset > 0 && !state.eventsHasMore)) return
        reduce(DetailsAction.EventsMoreStarted)
        scope.launch {
            when (val result = loadEvents(deviceId, offset)) {
                is Outcome.Success -> reduce(DetailsAction.EventsLoaded(result.value))
                is Outcome.Failure -> reduce(DetailsAction.EventsFailed(result.error))
            }
        }
    }
}
