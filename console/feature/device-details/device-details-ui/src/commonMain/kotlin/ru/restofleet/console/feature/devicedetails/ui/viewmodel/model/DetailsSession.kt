package ru.restofleet.console.feature.devicedetails.ui.viewmodel.model

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.contract.MviState
import ru.restofleet.console.feature.devicedetails.domain.DeviceCard
import ru.restofleet.console.feature.devicedetails.domain.DeviceEvent
import ru.restofleet.console.feature.devicedetails.domain.ProbeResult
import ru.restofleet.console.feature.devicedetails.domain.TempPoint

enum class DetailsTab(val title: String) {
    State("Состояние"),
    Probes("Проверки"),
    Events("События"),
    Shadow("Тень"),
}

/** Источник истины карточки. `null` у списков — вкладка ещё не открывалась. */
data class DetailsSession(
    val tab: DetailsTab = DetailsTab.State,
    val card: DeviceCard? = null,
    val loading: Boolean = true,
    val error: ConsoleError? = null,
    val temp: List<TempPoint> = emptyList(),
    val probes: List<ProbeResult>? = null,
    val probesError: ConsoleError? = null,
    val events: List<DeviceEvent>? = null,
    val eventsHasMore: Boolean = false,
    val eventsLoadingMore: Boolean = false,
    val eventsError: ConsoleError? = null,
    val commandSheet: Boolean = false,
    /** Последний известный момент времени (загрузка или push): от него считается возраст данных. */
    val knownNow: Long = 0L,
) : MviState
