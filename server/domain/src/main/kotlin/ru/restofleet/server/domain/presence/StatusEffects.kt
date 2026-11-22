package ru.restofleet.server.domain.presence

import ru.restofleet.server.domain.realtime.RealtimeEvent
import ru.restofleet.server.domain.shadow.DesiredState

/** Что `HandleStatus` делает после commit: повтор `desired`, событие статуса, тревоги для шины. */
internal class StatusEffects {
    var republish: DesiredState? = null
    var status: RealtimeEvent.DeviceStatusChanged? = null
    var alerts: List<String> = emptyList()
}
