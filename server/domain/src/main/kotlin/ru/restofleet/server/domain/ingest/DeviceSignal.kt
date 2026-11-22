package ru.restofleet.server.domain.ingest

/**
 * Типизированный смысл события устройства для правил (реш. 415): разбирает data-слой из того же сообщения `events`,
 * домен JSON не читает. Остальные типы событий сигнала не несут.
 */
sealed interface DeviceSignal {
    /** `threshold`: [metric] — `battery_low` | `temp_high` | `thermal`; [enter] — вход в зону (иначе выход). */
    data class Threshold(val metric: String, val enter: Boolean, val value: Double, val limit: Double) : DeviceSignal

    /** `fg_absent`: отслеживаемое приложение [pkg] не на экране дольше [limitMin] минут с [sinceTs]. */
    data class FgAbsent(val pkg: String, val sinceTs: Long, val limitMin: Int, val foregroundApp: String?) :
        DeviceSignal

    /** `fg_changed`: на экране теперь [to]. */
    data class FgChanged(val to: String?) : DeviceSignal

    /** `probe_state`: автомат K неудач цели [target] перешёл в `down` ([down]) или `up`. */
    data class ProbeState(val target: String, val down: Boolean, val error: String?, val failures: Int) :
        DeviceSignal

    companion object {
        const val BATTERY_LOW = "battery_low"
        const val TEMP_HIGH = "temp_high"
        const val THERMAL = "thermal"
    }
}
