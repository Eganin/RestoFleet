package ru.restofleet.agent.data.lifecycle

import ru.restofleet.agent.data.platform.HeartbeatGaps
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.shared.kernel.Clock

/** Запланирован ли сторож WorkManager (реализация — в `agent:app`). */
fun interface WatchdogSchedule {
    suspend fun isScheduled(): Boolean
}

/** Три флага мастера (L4); «Мастер» — шаги настройки устройства, а не Wizard-экран конкретной версии. */
interface WizardPermissions {
    fun notificationsEnabled(): Boolean
    fun usageAccessGranted(): Boolean
    fun ignoringBatteryOptimizations(): Boolean
}

/** Проверки группы LIFECYCLE L1–L4; L5 — задача 3.5. */
class LifecycleChecks(
    private val clock: Clock,
    private val heartbeat: HeartbeatLog,
    private val journal: LifecycleJournal,
    private val watchdog: WatchdogSchedule,
    private val permissions: WizardPermissions,
    private val wakeLock: CpuWakeLock,
) {
    fun create(): List<CapabilityCheck> = listOf(
        check(CheckId.L1, ::l1),
        check(CheckId.L2, ::l2),
        check(CheckId.L3, ::l3),
        check(CheckId.L4, ::l4),
    )

    private fun check(checkId: CheckId, body: suspend () -> Verdict) = object : CapabilityCheck {
        override val id = checkId
        override suspend fun run(): CheckResult {
            val v = body()
            return CheckResult(checkId, v.status, v.raw, v.note, clock.nowMillis())
        }
    }

    private class Verdict(val status: CheckStatus, val raw: Map<String, String>, val note: String? = null)

    private fun l1(): Verdict {
        val ts = heartbeat.timestamps()
        val coverage = if (ts.size < 2) 0L else ts.last() - ts.first()
        val gaps = HeartbeatGaps.gapsOver(ts, GAP_LIMIT_MS)
        val raw = mapOf(
            "samples" to "${ts.size}",
            "coverageMs" to "$coverage",
            "gaps" to "$gaps",
            "maxGapMs" to "${HeartbeatGaps.maxGapMillis(ts) ?: 0}",
            "wakeLock" to if (wakeLock.isHeld) "held" else "not_held",
        )
        return when {
            gaps > 0 -> Verdict(CheckStatus.LIMITED, raw, "heartbeat gaps over ${GAP_LIMIT_MS / MS_IN_SEC} s: $gaps")
            coverage >= MIN_COVERAGE_MS -> Verdict(CheckStatus.OK, raw)
            else -> Verdict(CheckStatus.NOT_CHECKED, raw, "coverage is shorter than 12 h")
        }
    }

    private fun l2(): Verdict {
        val boot = journal.lastBootAt()
            ?: return Verdict(CheckStatus.NOT_CHECKED, emptyMap(), "no BOOT_COMPLETED recorded yet")
        val first = heartbeat.timestamps().firstOrNull { it >= boot }
        return when {
            first != null -> Verdict(
                CheckStatus.OK,
                mapOf("bootAt" to "$boot", "firstHeartbeatAt" to "$first", "delayMs" to "${first - boot}"),
            )
            clock.nowMillis() - boot < BOOT_GRACE_MS ->
                Verdict(CheckStatus.NOT_CHECKED, mapOf("bootAt" to "$boot"), "waiting for first heartbeat after boot")
            else -> Verdict(
                CheckStatus.UNAVAILABLE,
                mapOf("bootAt" to "$boot"),
                "no heartbeat after boot",
            )
        }
    }

    private suspend fun l3(): Verdict {
        val scheduled = watchdog.isScheduled()
        val started = journal.lastWatchdogStartAt()
        val denied = journal.lastWatchdogDeniedAt()
        val lastStart = journal.lastStartAt()
        val lastFailure = journal.lastStartFailureAt()
        val raw = mapOf(
            "scheduled" to "$scheduled",
            "lastWatchdogStartAt" to (started?.toString() ?: NONE),
            "lastWatchdogDeniedAt" to (denied?.toString() ?: NONE),
            "lastStartFailureAt" to (lastFailure?.toString() ?: NONE),
        )
        return when {
            !scheduled -> Verdict(CheckStatus.UNAVAILABLE, raw, "watchdog is not scheduled")
            // LIMITED, только если последнее событие запуска — срыв; любой более поздний старт его снимает.
            lastFailure != null && (lastStart == null || lastFailure > lastStart) ->
                Verdict(CheckStatus.LIMITED, raw, "last launch attempt failed or was denied by the system")
            // OK — только с доказательством: сторож действительно перезапускал агента, причём после последнего срыва.
            started == null || (lastFailure != null && started <= lastFailure) ->
                Verdict(CheckStatus.NOT_CHECKED, raw, "watchdog has not restarted the agent yet")
            else -> Verdict(CheckStatus.OK, raw)
        }
    }

    private fun l4(): Verdict {
        val flags = linkedMapOf(
            "notifications" to permissions.notificationsEnabled(),
            "usageAccess" to permissions.usageAccessGranted(),
            "ignoreBatteryOptimizations" to permissions.ignoringBatteryOptimizations(),
        )
        val missing = flags.filterValues { !it }.keys
        val raw = flags.mapValues { "${it.value}" }
        return if (missing.isEmpty()) {
            Verdict(CheckStatus.OK, raw)
        } else {
            Verdict(CheckStatus.LIMITED, raw, "missing: ${missing.joinToString()}")
        }
    }

    private companion object {
        const val MS_IN_SEC = 1_000L
        const val GAP_LIMIT_MS = 120_000L
        const val MIN_COVERAGE_MS = 12 * 60 * 60 * MS_IN_SEC
        const val BOOT_GRACE_MS = 120_000L
        const val NONE = "none"
    }
}
