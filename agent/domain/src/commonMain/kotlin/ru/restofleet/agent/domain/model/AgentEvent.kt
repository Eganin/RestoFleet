package ru.restofleet.agent.domain.model

import ru.restofleet.agent.domain.capability.CheckResult

/** Событие агента (топик `events`). [ts] — wall clock, epoch millis, как в протоколе. */
sealed interface AgentEvent {
    val ts: Long

    data class ProbeStateChanged(
        override val ts: Long,
        val target: String,
        val up: Boolean,
        val error: ProbeError?,
        val failures: Int,
    ) : AgentEvent

    data class NetworkChanged(override val ts: Long, val state: NetworkState) : AgentEvent

    data class Lifecycle(override val ts: Long, val phase: Phase) : AgentEvent {
        enum class Phase { BOOT, START, STOP }
    }

    data class PackageChanged(
        override val ts: Long,
        val pkg: String,
        val change: Change,
        val versionCode: Long?,
    ) : AgentEvent {
        enum class Change { ADDED, REPLACED, REMOVED }
    }

    /** Серийный номер и прочее приходят в [result] уже маскированными у источника (R13), здесь не дополняются. */
    data class CapabilityChecked(override val ts: Long, val result: CheckResult) : AgentEvent

    /**
     * Пересечение порога (FR-AG-40, реш. 202, 218): [entered] — вход в зону (`enter`), иначе выход (`exit`, с
     * гистерезисом). [value] и [limit] — в единицах [metric]: %, °C или код теплового статуса 0..6.
     */
    data class ThresholdCrossed(
        override val ts: Long,
        val metric: ThresholdMetric,
        val entered: Boolean,
        val value: Double,
        val limit: Double,
    ) : AgentEvent

    /** Смена приложения на переднем плане (FR-AG-41, реш. 220); `null` — приложение неизвестно. */
    data class ForegroundChanged(override val ts: Long, val from: String?, val to: String?) : AgentEvent

    /**
     * Отслеживаемое приложение [pkg] не на экране дольше [limitMin] минут (FR-AG-41, реш. 220): одно событие на
     * эпизод отсутствия, [sinceTs] — не позже момента, когда оно ушло с экрана; [foregroundApp] — что на экране сейчас.
     */
    data class ForegroundAbsent(
        override val ts: Long,
        val pkg: String,
        val sinceTs: Long,
        val limitMin: Int,
        val foregroundApp: String?,
    ) : AgentEvent

    /**
     * Только debug (проверка M7): последовательность из [seq] 1..N в пачке [batchId]. Сервер такие события
     * игнорирует; в release они не создаются.
     */
    data class DebugSeq(override val ts: Long, val seq: Int, val batchId: String) : AgentEvent
}

/** Метрика порога: заряд ниже `batteryLowPct`, температура батареи выше `tempHighC`, тепловой статус от `SEVERE`. */
enum class ThresholdMetric { BATTERY_LOW, TEMP_HIGH, THERMAL }
