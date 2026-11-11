package ru.restofleet.server.domain.presence

/** Причины закрытия сессии, которые выставляет сам бэкенд (агентские — `lwt`, `stop` — приходят в `status`). */
object SessionCloseReason {
    const val DECOMMISSIONED = "decommissioned"
    const val REPLACED = "replaced"
}
