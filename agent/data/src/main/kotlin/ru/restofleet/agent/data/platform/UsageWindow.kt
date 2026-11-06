package ru.restofleet.agent.data.platform

/**
 * Окно запроса `UsageEvents` для определения приложения переднего плана.
 *
 * События — только переходы: приложение, не уходившее с экрана часами, в коротком окне не оставляет ни одного. Поэтому
 * первый запрос после старта процесса идёт с момента загрузки устройства (`now - elapsedRealtime`), а следующие — с
 * прошлого запроса минус [OVERLAP_MS] (события приходят с задержкой); окно никогда не начинается раньше загрузки.
 */
object UsageWindow {
    /** Нахлёст с прошлым запросом: поздние события не теряются, повторы безвредны. */
    const val OVERLAP_MS = 60_000L

    fun startMillis(now: Long, elapsedRealtime: Long, lastQueryAt: Long?): Long {
        val bootAt = (now - elapsedRealtime).coerceAtLeast(0L)
        return if (lastQueryAt == null) bootAt else (lastQueryAt - OVERLAP_MS).coerceIn(bootAt, maxOf(bootAt, now))
    }
}
