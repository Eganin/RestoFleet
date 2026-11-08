package ru.restofleet.agent.domain.model

/** Итог досылки outbox: [sent] — отправлено за вызов, [remaining] — сколько записей осталось. */
data class FlushResult(val sent: Int, val remaining: Int)
