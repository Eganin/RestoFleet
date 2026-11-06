package ru.restofleet.agent.data.platform

import ru.restofleet.agent.domain.model.ForegroundApp

/** Событие UsageStatsManager: [type] — UsageEvents.Event.*, [ts] — epoch millis. */
data class UsageEventRaw(val pkg: String, val type: Int, val ts: Long)

object ForegroundAppResolver {
    /** UsageEvents.Event.ACTIVITY_RESUMED */
    private const val ACTIVITY_RESUMED = 1

    /** UsageEvents.Event.ACTIVITY_PAUSED */
    private const val ACTIVITY_PAUSED = 2

    /** Приложение переднего плана: последнее RESUMED, если после него тот же пакет не ушёл в PAUSED. */
    fun resolve(events: List<UsageEventRaw>): ForegroundApp? {
        val resumed = events.filter { it.type == ACTIVITY_RESUMED }.maxByOrNull { it.ts } ?: return null
        val paused = events.any { it.type == ACTIVITY_PAUSED && it.pkg == resumed.pkg && it.ts > resumed.ts }
        return if (paused) null else ForegroundApp(resumed.pkg, resumed.ts)
    }
}
