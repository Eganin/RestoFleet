package ru.restofleet.agent.domain.command

/** Статусы `cmd/res` (ТЗ 7.2) как строки протокола; domain не зависит от enum из `shared:protocol`. */
object CommandStatus {
    const val RECEIVED = "received"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val UNSUPPORTED = "unsupported"
}
