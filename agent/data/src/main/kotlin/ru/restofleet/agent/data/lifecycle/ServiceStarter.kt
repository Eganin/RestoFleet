package ru.restofleet.agent.data.lifecycle

/** Гарантирует запуск foreground-сервиса агента; [reason] фиксируется в журнале (boot, watchdog, manual…). */
fun interface ServiceStarter {
    fun ensureRunning(reason: String)
}
