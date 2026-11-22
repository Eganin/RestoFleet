package ru.restofleet.agent.domain.threshold

import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.ForegroundApp

/**
 * Приложение на переднем плане (FR-AG-41, реш. 220):
 * - `fg_changed{from, to}` — на каждую смену; первое наблюдение после запуска — точка отсчёта, без события;
 * - `fg_absent` — отслеживаемое приложение не на экране дольше `fgAbsentMin` минут, один раз на эпизод. Эпизод
 *   начинается не позже момента, когда на экран вышло текущее приложение ([ForegroundApp.sinceMillis], но не позже
 *   времени тика), и кончается, когда отслеживаемое приложение вернулось. Убранное из списка приложение забывается.
 *   Лимит меньше 1 мин отключает `fg_absent`.
 * Передний план неизвестен (нет usage access, сбой) — состояние не меняется, событий нет. Не потокобезопасен.
 */
class ForegroundTracker {
    private var current: String? = null
    private val absentSince = mutableMapOf<String, Long>()
    private val reported = mutableSetOf<String>()

    fun evaluate(ts: Long, foreground: ForegroundApp?, watched: List<String>, limitMin: Int): List<AgentEvent> {
        if (foreground == null) return emptyList()
        val events = mutableListOf<AgentEvent>()
        val previous = current
        if (previous != null && previous != foreground.pkg) {
            events += AgentEvent.ForegroundChanged(ts, previous, foreground.pkg)
        }
        current = foreground.pkg

        absentSince.keys.retainAll(watched.toSet())
        reported.retainAll(watched.toSet())
        for (pkg in watched.distinct()) {
            if (pkg == foreground.pkg) {
                absentSince -= pkg
                reported -= pkg
                continue
            }
            val since = absentSince.getOrPut(pkg) { minOf(ts, foreground.sinceMillis) }
            if (limitMin >= 1 && pkg !in reported && ts - since >= limitMin * MS_IN_MINUTE) {
                reported += pkg
                events += AgentEvent.ForegroundAbsent(ts, pkg, since, limitMin, foreground.pkg)
            }
        }
        return events
    }

    private companion object {
        const val MS_IN_MINUTE = 60_000L
    }
}
