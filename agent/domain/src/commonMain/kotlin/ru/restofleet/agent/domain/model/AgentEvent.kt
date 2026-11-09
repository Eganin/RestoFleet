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
     * Только debug (проверка M7): последовательность из [seq] 1..N в пачке [batchId]. Сервер такие события
     * игнорирует; в release они не создаются.
     */
    data class DebugSeq(override val ts: Long, val seq: Int, val batchId: String) : AgentEvent
}
